// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.aaf;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;


/**
 * Reads an AAF file (Advanced Authoring Format).
 *
 * An AAF file is an OLE2 Compound File (Microsoft Structured Storage, the AAF Structured
 * File Format, SSF). Apache POI provides access to the storage structure (storages and
 * streams) but not to the AAF object model. This parser implements the AAF-SSF decoding:
 *
 * - Every object (class instance) is represented by a storage (directory). Its simple
 *   properties are stored in a stream named "properties" with this layout:
 *   2 bytes marker (0x4c 0x20), 2 bytes count (little endian), then for each
 *   property a 6 byte entry: 2 bytes local property id (pid), 2 bytes stored form,
 *   2 bytes value length - followed by all property values concatenated in entry order.
 * - Strong references to child objects are stored as sub-storages named
 *   "&lt;PropertyName&gt;-&lt;pid in hex&gt;{&lt;key&gt;}". The property value of a strong
 *   reference (stored form 0x22) or strong reference vector (stored form 0x32) is the
 *   UTF-16 name of the referenced storage.
 * - The order of the elements of a reference vector is stored in a stream named
 *   "&lt;PropertyName&gt;-&lt;pid&gt; index" next to the element storages: a 12 byte header
 *   (count, count, -1) followed by the element keys (4 bytes each) in timeline order.
 * - 16 byte AUIDs are stored rotated: the last 8 bytes first, then the first 8 bytes.
 * - The MetaDictionary maps the file local pids to property names (PropertyDefinition
 *   objects store their pid as a 16 bit value and their name as a string).
 *
 * From the object model the parser reconstructs the timeline: CompositionMob ->
 * TimelineMobSlot (Edit Rate) -> Sequence -> components (SourceClip, Filler,
 * OperationGroup, ...). The position of each component is the accumulated length of all
 * previous components divided by the Edit Rate of the slot. A SourceClip provides the
 * MobID of its source (SourceID) and the trim offset into the source media (StartTime).
 *
 * Embedded audio essence (WAV data in streams below EssenceData objects) is extracted to
 * a temporary folder and matched to the clips via the MobIDs. External essence references
 * are resolved by following the reference chain from SourceMobs to their EssenceDescription
 * and reading the external file paths, sample rate and channel count from there.
 *
 * @author J&uuml;rgen Mo&szlig;graber
 */
public class AAFParser
{
    /** Optional debug listener for diagnostics (may be null). */
    static java.util.function.Consumer<String> debugListener;
    /** Maximum size of a property values stream which is processed (16 MB). */
    private static final int MAX_PROPERTY_SIZE = 16 * 1024 * 1024;

    // AAF SSF stored forms (as observed in real files)
    /** Weak object reference. */
    private static final int FORM_WEAK_REF          = 0x02;
    /** Reference to an essence data stream. */
    private static final int FORM_DATA_STREAM       = 0x42;
    /** Strong reference to a single object (value: UTF-16 name of the target storage). */
    private static final int FORM_STRONG_REF        = 0x22;
    /** Strong reference vector (value: UTF-16 name of the vector storage). */
    private static final int FORM_STRONG_VECTOR     = 0x32;
    /** Simple data value (integer, rational, AUID, string, ...). */
    private static final int FORM_DATA              = 0x82;

    // Fixed pids of the meta model (the meta dictionary is encoded with the same SSF
    // format but uses its own well-known property ids)
    private static final int META_PID_IDENTIFICATION = 0x05;
    private static final int META_PID_NAME           = 0x06;
    private static final int META_PID_PID            = 0x0d;

    /** Typical edit rate values in Hz (or frames per second). */
    private static final double [] TYPICAL_EDIT_RATES =
    {
        23.976, 24, 25, 29.97, 30, 48, 50, 59.94, 60, 100, 120, 32000, 44100, 48000, 88200, 96000, 192000
    };


    /**
     * An audio clip extracted from an AAF file.
     */
    public static class AudioClip
    {
        /** The extracted audio file (temporary WAV file). */
        public final File   audioFile;
        /** The name of the OLE2 stream the audio was extracted from. */
        public final String streamName;
        /** Duration of the audio in seconds. */
        public final double durationSeconds;
        /** The MobID of the SourceMob this essence belongs to (hex string). */
        public String       mobID = "";
        /** True if this clip has already been assigned to a positioned clip entry. */
        public boolean      used = false;


        /**
         * Constructor.
         *
         * @param audioFile       The extracted audio file
         * @param streamName      The name of the stream it was extracted from
         * @param durationSeconds The duration in seconds
         */
        AudioClip (final File audioFile, final String streamName, final double durationSeconds)
        {
            this.audioFile = audioFile;
            this.streamName = streamName;
            this.durationSeconds = durationSeconds;
        }
    }


    /**
     * A clip entry with its timeline position. The position is the accumulated length of
     * all previous components of the sequence divided by the edit rate of the slot.
     */
    public static class ClipEntry
    {
        /** Start position on the timeline in edit rate ticks. */
        public long   startTicks;
        /** Duration on the timeline in edit rate ticks. */
        public long   lengthTicks;
        /** Start position on the timeline in seconds. */
        public double startSeconds;
        /** Duration on the timeline in seconds. */
        public double durationSeconds;
        /** Offset into the source media in seconds (the SourceClip StartTime value). */
        public double sourceOffsetSeconds;
        /** The audio channel of the clip within the track (0 for mono). */
        public int      channel;
        /** The source MobID (hex string) of the referenced media or null. */
        public String sourceMobID;
        /** The source track id (channel number of the source media, 1-based). */
        public int    sourceTrackID;
        /** The extracted audio file, if it could be matched. */
        public AudioClip audio;


        /**
         * Constructor.
         *
         * @param startTicks  The start position in edit rate ticks
         * @param lengthTicks The duration in edit rate ticks
         * @param editRate    The edit rate of the slot
         */
        public ClipEntry (final long startTicks, final long lengthTicks, final double editRate)
        {
            this.startTicks = startTicks;
            this.lengthTicks = lengthTicks;
            this.startSeconds = editRate > 0 ? (double) startTicks / editRate : 0;
            this.durationSeconds = editRate > 0 ? (double) lengthTicks / editRate : 0;
        }
    }


    /**
     * A timeline track reconstructed from an AAF TimelineMobSlot.
     */
    public static class Track
    {
        /** The name of the track (slot or mob name) if available, otherwise a generated name. */
        public String             name = "";
        /** The edit rate of the slot in Hz (or frames per second). */
        public double             editRateHz = 0;
        /** The clips of the track in timeline order. */
        public List<ClipEntry>    clips = new ArrayList<> ();
    }


    /**
     * The result of parsing an AAF file.
     */
    public static class AafProject
    {
        /** The reconstructed tracks with positioned clips. */
        public final List<Track>     tracks     = new ArrayList<> ();
        /** All extracted embedded audio clips (fallback, unordered). */
        public final List<AudioClip> audioClips = new ArrayList<> ();
        /** The total duration of the project in seconds. */
        public double                totalDurationSeconds = 0;
    }


    /**
     * A property value: the stored form and the raw data.
     */
    private static class Prop
    {
        final int    form;
        final byte [] data;

        Prop (final int form, final byte [] data)
        {
            this.form = form;
            this.data = data;
        }
    }


    /**
     * An AAF object: a storage in the OLE2 file with its properties and children.
     */
    private static class AafObj
    {
        /** The OLE2 path of the object (for diagnostics). */
        String                          path         = "";
        final String                    name;
        /** Properties by local pid. */
        final Map<Integer, Prop>        props        = new HashMap<> ();
        /** Properties by readable name (applied from the meta dictionary). */
        final Map<String, Prop>         namedProps   = new HashMap<> ();
        /** Children by OLE2 storage name. */
        final Map<String, AafObj>       childrenByName = new HashMap<> ();
        /** Raw bytes of the "&lt;name&gt; index" streams (vector order) by vector name. */
        final Map<String, byte []>      indexStreams = new HashMap<> ();
        /** The extracted audio essence, if this is an EssenceData object. */
        AudioClip                       essence;
        /** Candidate essence streams (OLE2 document entries) found below this object. */
        final List<DocumentEntry>       essenceCandidates = new ArrayList<> ();


        AafObj (final String name)
        {
            this.name = name;
        }


        Prop get (final String propertyName)
        {
            return this.namedProps.get (propertyName);
        }
    }


    /**
     * Parses an AAF file: extracts embedded audio and reconstructs the timeline.
     *
     * @param aafFile      The AAF file to read
     * @param outputFolder The folder where to write the extracted audio files
     * @return The parsed project
     * @throws IOException Could not read the file
     */
    public static AafProject parse (final File aafFile, final File outputFolder) throws IOException
    {
        final AafProject project = new AafProject ();
        final List<AafObj> allObjects = new ArrayList<> ();
        final List<Object []> essenceStreamCandidates = new ArrayList<> ();

        try (POIFSFileSystem fileSystem = new POIFSFileSystem (aafFile, true))
        {
            final AafObj root = new AafObj ("/");
            scanStorage (fileSystem.getRoot (), root, allObjects, essenceStreamCandidates, project);

            // Build the pid -> property name map from the meta dictionary
            final Map<Integer, String> pidNames = new HashMap<> ();
            buildDictionary (root, pidNames);
            applyNames (allObjects, pidNames);

            if (debugListener != null)
                debugListener.accept ("pidNames: " + pidNames.size ());

            // Extract embedded audio and resolve external references
            extractEssenceAndReferences (essenceStreamCandidates, allObjects, outputFolder, project);

            buildTracks (allObjects, project);
        }

        for (final Track track: project.tracks)
            for (final ClipEntry entry : track.clips)
                project.totalDurationSeconds = Math.max (project.totalDurationSeconds, entry.startSeconds + entry.durationSeconds);

        return project;
    }


    /**
     * Recursively scans an OLE2 storage and builds the AAF object tree.
     */
    private static void scanStorage (final DirectoryEntry directory, final AafObj parent, final List<AafObj> allObjects, final List<Object []> essenceStreamCandidates, final AafProject project) throws IOException
    {
        allObjects.add (parent);

        for (final Entry entry : directory)
        {
            if (entry instanceof final DirectoryEntry subDirectory)
            {
                final AafObj object = new AafObj (entry.getName ());
                object.path = parent.path + "/" + object.name;
                parent.childrenByName.put (object.name, object);
                scanStorage (subDirectory, object, allObjects, essenceStreamCandidates, project);
                continue;
            }

            if (entry instanceof final DocumentEntry document)
            {
                final String entryName = entry.getName ();
                if ("properties".equals (entryName))
                {
                    final Map<Integer, Prop> props = parsePropertiesStream (document);
                    if (props != null)
                        parent.props.putAll (props);
                    continue;
                }

                if (entryName.endsWith (" index"))
                {
                    // Vector order index of the sibling vector storage
                    final byte [] data = readStream (document);
                    if (data != null)
                        parent.indexStreams.put (entryName.substring (0, entryName.length () - 6), data);
                    continue;
                }

                // Any other stream could be essence data (audio); check for RIFF/WAVE
                if (isWaveStream (document))
                    essenceStreamCandidates.add (new Object []
                    {
                        parent, document
                    });
            }
        }
    }


    /**
     * Reads an AAF "properties" stream. Layout: 2 bytes marker (0x4c 0x20), 2 bytes count
     * (little endian), count times 6 bytes entry (2 bytes pid, 2 bytes stored form,
     * 2 bytes value length), then all values concatenated in entry order.
     */
    private static Map<Integer, Prop> parsePropertiesStream (final DocumentEntry document) throws IOException
    {
        final long streamSize = document.getSize ();
        if (streamSize < 4 || streamSize > MAX_PROPERTY_SIZE)
            return null;

        final byte [] data = readStream (document);
        if (data == null || data.length < 4 || data [0] != 0x4c || data [1] != 0x20)
            return null;

        final int count = readUInt16LE (data, 2);
        if (count <= 0 || 4 + count * 6 > data.length)
            return null;

        final int [] pids = new int [count];
        final int [] forms = new int [count];
        final int [] lengths = new int [count];
        int pos = 4;
        for (int i = 0; i < count; i++)
        {
            pids[i] = readUInt16LE (data, pos);
            forms[i] = readUInt16LE (data, pos + 2);
            lengths[i] = readUInt16LE (data, pos + 4);
            pos += 6;
        }

        final Map<Integer, Prop> result = new HashMap<> ();
        for (int i = 0; i < count; i++)
        {
            if (pos + lengths[i] > data.length)
                break;
            final byte [] value = new byte [lengths[i]];
            System.arraycopy (data, pos, value, 0, lengths[i]);
            result.put (Integer.valueOf (pids[i]), new Prop (forms[i], value));
            pos += lengths[i];
        }
        return result;
    }


    /**
     * Builds the property name map from the MetaDictionary: ClassDefinition objects
     * contain PropertyDefinition objects, which store their local pid (16 bit) and their
     * human readable name.
     */
    private static void buildDictionary (final AafObj root, final Map<Integer, String> pidNames)
    {
        AafObj metaDictionary = null;
        for (final AafObj child : root.childrenByName.values ())
            if (child.name.startsWith ("MetaDictionary"))
            {
                metaDictionary = child;
                break;
            }
        if (metaDictionary == null)
            return;

        // The class definitions are the children of the meta dictionary named
        // "ClassDefinitions-&lt;pid&gt;{&lt;key&gt;}"" (strong reference vector elements are stored
        // directly in the owning object's storage)
        for (final AafObj classDef : metaDictionary.childrenByName.values ())
        {
            if (!classDef.name.startsWith ("ClassDefinitions") || !classDef.name.contains ("{"))
                continue;

            // The children of a class definition named "Properties-*" are its property
            // definitions
            for (final AafObj propertyDef : classDef.childrenByName.values ())
            {
                if (!propertyDef.name.startsWith ("Properties-"))
                    continue;

                final Prop pidProp = propertyDef.props.get (Integer.valueOf (META_PID_PID));
                final Prop nameProp = propertyDef.props.get (Integer.valueOf (META_PID_NAME));
                if (pidProp == null || nameProp == null || pidProp.data.length < 2)
                    continue;

                final int pid = readUInt16LE (pidProp.data, 0);
                final String name = decodeUtf16 (nameProp.data);
                if (pid > 0 && name != null && !name.isEmpty ())
                    pidNames.putIfAbsent (Integer.valueOf (pid), name);
            }
        }
    }


    /**
     * Applies the property names from the meta dictionary to all objects.
     */
    private static void applyNames (final List<AafObj> allObjects, final Map<Integer, String> pidNames)
    {
        for (final AafObj object : allObjects)
        {
            for (final Map.Entry<Integer, Prop> entry : object.props.entrySet ())
            {
                final String name = pidNames.get (entry.getKey ());
                object.namedProps.put (name == null ? String.format ("pid_%04x", Integer.valueOf (entry.getKey ().intValue ())) : name, entry.getValue ());
            }
        }
    }


    /**
     * Extracts embedded audio essence and resolves external references.
     *
     * Embedded audio is extracted from WAVE streams found below EssenceData objects.
     * External references are resolved by following the chain: SourceClip -> SourceMob ->
     * EssenceDescription (which contains the external file path, sample rate and channel count)
     * or via the EssenceFileReference property in the SourceMob itself.
     */
    private static void extractEssenceAndReferences(final List<Object[]> candidates, final List<AafObj> allObjects, final File outputFolder, final AafProject project) throws IOException
    {
        // --- Step 1: Extract embedded audio essence from WAVE streams ---
        int counter = 0;
        for (final Object[] candidate : candidates)
        {
            final AafObj essenceData = (AafObj) candidate[0];
            final DocumentEntry document = (DocumentEntry) candidate[1];

            // Check if this is below an EssenceData object by looking at the parent chain.
            // The "ClassName" property has pid 6 (0x06) and contains a UTF-16 string.
            boolean isEssenceDataChild = false;
            for (AafObj obj : new ArrayList<>(List.of(essenceData)))
            {
                final Prop classNameProp = obj.get("ClassName");
                if (classNameProp != null && decodeUtf16(classNameProp.data) != null)
                {
                    if ("EssenceData".equals(decodeUtf16(classNameProp.data)))
                        isEssenceDataChild = true;
                    break; // Only need to check the immediate parent for EssenceData
                }
            }

            // Also check via the property stream for SourceMobID (pid 5, 0x05)
            if (!isEssenceDataChild && essenceData.get("SourceMobID") != null)
                continue; // This is a SourceClip, not an EssenceData object

            final File extractedFile = extractWaveFile(document, outputFolder, counter++);
            if (extractedFile == null)
                continue;

            // The MobID of the EssenceData links it to the SourceMob: a 32 byte MobID property.
            Prop mobIDProp = essenceData.get("MobID");
            if (mobIDProp == null || mobIDProp.data.length != 32)
            {
                // Fallback: search for any 32-byte data property that looks like a MobID
                for (final Prop prop : essenceData.props.values())
                    if (prop.form == FORM_DATA && prop.data.length == 32)
                    {
                        mobIDProp = prop;
                        break;
                    }
            }

            AudioClip clip = null;
            if (mobIDProp != null && mobIDProp.data.length == 32)
            {
                String mobIDHex = toHex(mobIDProp.data);
                clip = new AudioClip(extractedFile, document.getName(), getWaveDuration(extractedFile));
                clip.mobID = mobIDHex;
                project.audioClips.add(clip);
            }
        }

        // --- Step 2: Resolve external media references by following the reference chain ---
        buildExternalReferences(allObjects, outputFolder, project);
    }


    /**
     * Resolves all external media references found in the AAF file.
     *
     * For each SourceMob that references an external essence (via EssenceFileReference or
     * a path stored in EssenceDescription), this method:
     * 1. Reads the external file path from EssenceDescription (or EssenceFileReference)
     * 2. Extracts the audio if not already extracted
     * 3. Records it in project.audioClips with the sourceMobID as key
     */
    private static void buildExternalReferences(final List<AafObj> allObjects, final File outputFolder, final AafProject project) throws IOException
    {
        // Build a map from MobID -> AafObj for quick lookup
        final Map<String, AafObj> mobByMobID = new HashMap<> ();
        for (final AafObj mob : allObjects)
            if (getMobID(mob, "MobID") != null)
                mobByMobID.put(getMobID(mob, "MobID"), mob);

        // For each SourceClip, follow the chain to find external references
        final Set<String> processedClips = new HashSet<> ();

        for (final AafObj mob : allObjects)
        {
            // Look for EssenceDescription children which might contain external paths
            for (AafObj child : mob.childrenByName.values ())
                if (child.name.startsWith ("EssenceDescription"))
                {
                    processExternalEssence(mob, child, outputFolder, project, mobByMobID);
                }

            // Also check SourceMobs that have EssenceFileReference or stored paths
            final String ownMobID = getMobID(mob, "MobID");
            if (ownMobID != null && hasSourceClipReferences(mob) && !isZeroMobID(ownMobID))
            {
                // Check for EssenceFileReference property in the SourceMob itself
                Prop refProp = mob.get ("EssenceFileReference");
                if (refProp == null || refProp.form != FORM_STRONG_REF)
                    continue;

                String refPathStr = decodeUtf16 (refProp.data);
                if (refPathStr != null && !refPathStr.isEmpty ())
                {
                    // Resolve the reference path
                    processExternalEssence (mob, mob, outputFolder, project, mobByMobID);
                }
            }
        }
    }


    /**
     * Processes an external essence found in an EssenceDescription or SourceMob.
     * Reads the file path, sample rate and channel count from the AAF properties.
     */
    private static void processExternalEssence(final AafObj ownerMob, final AafObj sourceMobOrDesc, final File outputFolder, final AafProject project, final Map<String, AafObj> mobByMobID) throws IOException
    {
        // Read external file path
        String externalPath = null;

        Prop fileRefProp = sourceMobOrDesc.get ("EssenceFileReference");
        if (fileRefProp != null && fileRefProp.form == FORM_STRONG_REF && fileRefProp.data.length >= 2)
        {
            String refDirName = decodeUtf16 (fileRefProp.data);
            if (refDirName != null)
                externalPath = resolveReferencePath(sourceMobOrDesc, refDirName, mobByMobID);
        }

        // Fallback: EssenceDescription might store the path directly in a data property
        if (externalPath == null)
        {
            for (final Prop prop : sourceMobOrDesc.props.values ())
                if (prop.form == FORM_DATA && isAbsolutePath(prop.data))
                {
                    externalPath = decodeUtf16 (prop.data);
                    break;
                }
        }

        // Also try to find the path in children of the essence description: the AAF
        // standard stores external references in a Locator object (NetworkLocator) whose
        // URLString property contains the location of the media file.
        if (externalPath == null)
        {
            for (final AafObj child : sourceMobOrDesc.childrenByName.values ())
            {
                if (child.name.startsWith ("Locator"))
                {
                    // The URLString property (pid 0x8240) or any data property which
                    // looks like a file URL or path
                    Prop urlProp = child.get ("URLString");
                    if (urlProp == null)
                        for (final Prop prop: child.props.values ())
                            if (prop.form == FORM_DATA)
                            {
                                final String text = decodeUtf16 (prop.data);
                                if (text != null && (text.startsWith ("file:") || isAbsolutePath (prop.data)))
                                {
                                    urlProp = prop;
                                    break;
                                }
                            }
                    if (urlProp != null)
                        externalPath = toFilePath (decodeUtf16 (urlProp.data));
                }
                else if (child.name.startsWith ("EssenceFileReference"))
                {
                    for (final Prop prop: child.props.values ())
                        if (prop.form == FORM_DATA)
                        {
                            final String text = decodeUtf16 (prop.data);
                            if (text != null)
                                externalPath = toFilePath (text);
                        }
                }
                else if (child.name.startsWith ("ExternalMedia") || child.name.startsWith ("FileReference"))
                {
                    final Prop pathProp = child.get ("Path");
                    if (pathProp != null && isAbsolutePath (pathProp.data))
                        externalPath = toFilePath (decodeUtf16 (pathProp.data));
                }

                if (externalPath != null)
                    break;
            }
        }

        if (externalPath == null)
            return;

        // Read sample rate and channel count from EssenceDescription
        double sampleRate = 48000;
        int channels = 2;

        Prop srProp = sourceMobOrDesc.get ("SampleRate");
        if (srProp != null && srProp.data.length >= 8)
            try
            {
                long num = readUInt32LE(srProp.data, 0);
                long den = readUInt32LE(srProp.data, 4);
                if (den > 0) sampleRate = (double) num / den;
            }
        catch (Exception e) {}

        Prop chProp = sourceMobOrDesc.get ("ChannelCount");
        if (chProp != null && chProp.form == FORM_DATA && chProp.data.length >= 4)
            channels = (int) readUInt32LE(chProp.data, 0);

        // Extract the audio file from the external path
        final String filePath = toFilePath (externalPath);
        if (filePath == null)
            return;
        externalPath = filePath;
        File externalFile = new File (externalPath);
        if (!externalFile.exists ())
        {
            project.audioClips.add (new AudioClip (null, externalPath, 0));
            return;
        }

        final double duration = getWaveDuration (externalFile);
        if (duration <= 0)
            return;

        // Create an AudioClip representing the external reference
        AudioClip clip = new AudioClip (externalFile, externalPath, duration);
        // The MobID of the source mob links the external media to the clips referencing it
        final String mobID = getMobID (ownerMob, "MobID");
        clip.mobID = mobID == null ? "" : mobID;

        // Register this audio with the project and the mobByMobID map
        if (!clip.mobID.isEmpty ())
            project.audioClips.add (clip);
    }


    /**
     * Resolves a reference path that may contain relative components like "." or ".."
     */
    private static String resolveReferencePath(final AafObj owner, final String refDirName, final Map<String, AafObj> mobByMobID)
    {
        if (!refDirName.contains ("..") && !refDirName.contains ("\\"))
            return refDirName;

        // Normalize the path by resolving . and .. components
        List<String> parts = new ArrayList<> ();
        for (String part : refDirName.split ("\\\\"))
            if (part.isEmpty () || ".".equals(part))
                continue;
            else if ("..".equals(part) && !parts.isEmpty ())
                parts.remove(parts.size() - 1);
            else
                parts.add(part);

        return String.join(File.separator, parts);
    }


    /**
     * Checks whether an AAF object has SourceClip children (indicating it is a source mob).
     */
    private static boolean hasSourceClipReferences(final AafObj mob)
    {
        final Prop slotsProp = mob.get ("Slots");
        if (slotsProp == null || slotsProp.form != FORM_STRONG_VECTOR)
            return false;

        final String slotsDirName = decodeUtf16(slotsProp.data);
        if (slotsDirName == null)
            return false;

        for (final AafObj slot : orderedChildren(mob, slotsDirName))
        {
            final String segmentName = strongRefName(slot, "Segment");
            if (segmentName == null)
                continue;
            final AafObj segment = mob.childrenByName.get(segmentName);
            if (segment == null)
                continue;

            // Check if this is a SourceClip or contains one in its sequence
            if (getMobID(segment, "SourceID") != null && !isZeroMobID(getMobID(segment, "SourceID")))
                return true;

            final String componentsDirName = strongVectorName(segment, "Components");
            if (componentsDirName != null)
            {
                for (final AafObj component : orderedChildren(segment, componentsDirName))
                    if (getMobID(component, "SourceID") != null && !isZeroMobID(getMobID(component, "SourceID")))
                        return true;
            }
        }
        return false;
    }


    /**
     * Checks whether a property value is an absolute file path.
     */
    private static boolean isAbsolutePath(final byte[] data)
    {
        if (data == null || data.length < 4)
            return false;

        final String text = toFilePath (decodeUtf16 (data));
        if (text == null)
            return false;

        if (text.startsWith ("/") || text.startsWith ("\\"))
            return true;

        // Windows drive letter, e.g. C:\...
        return text.length () >= 3 && text.charAt (1) == ':' && (text.charAt (2) == '\\' || text.charAt (2) == '/');
    }


    /**
     * Converts an AAF file reference into a local file path. References may be stored as
     * plain paths or as URIs like file://localhost/C:/audio/file.wav. Percent encoded
     * characters are decoded.
     *
     * @param reference The raw reference text or null
     * @return The local file path or null
     */
    private static String toFilePath (final String reference)
    {
        if (reference == null)
            return null;

        String path = reference.trim ();

        // Strip file URI prefixes
        final String lower = path.toLowerCase (java.util.Locale.US);
        if (lower.startsWith ("file://"))
            path = path.substring (7);
        else if (lower.startsWith ("file:"))
            path = path.substring (5);

        // Remove an authority part (localhost)
        if (path.startsWith ("localhost/"))
            path = path.substring ("localhost".length ());

        // A leading slash before a drive letter is not part of the path (file:///C:/...)
        if (path.length () >= 3 && path.charAt (0) == '/' && path.charAt (2) == ':')
            path = path.substring (1);

        // Decode percent encoded characters (URL encoding)
        if (path.indexOf ('%') >= 0)
        {
            try
            {
                path = java.net.URLDecoder.decode (path, StandardCharsets.UTF_8);
            }
            catch (final IllegalArgumentException ex)
            {
                // Keep the raw path
            }
        }

        return path.isEmpty () ? null : path;
    }


    /**
     * Finds the index of the first non-zero byte in a UTF-16 string.
     */
    private static int findFirstNonZeroByte(final byte[] data)
    {
        for (int i = 0; i < data.length - 1 && i % 2 == 0; i++)
            if (data[i] != 0 || data[i+1] != 0)
                return i / 2; // Return character index
        return -1;
    }


    /**
     * Reconstructs the timeline tracks from the object tree.
     */
    private static void buildTracks(final List<AafObj> allObjects, final AafProject project)
    {
        // Audio files indexed by the MobID of their source mob (ordered by essence key)
        final Map<String, List<AudioClip>> audioByMobID = new HashMap<> ();
        for (final AudioClip clip : project.audioClips)
            if (!clip.mobID.isEmpty ())
                audioByMobID.computeIfAbsent(clip.mobID, k -> new ArrayList<>()).add(clip);

        // All mobs by their MobID
        final Map<String, AafObj> mobByMobID = new HashMap<> ();
        for (final AafObj mob : allObjects)
        {
            final String mobID = getMobID(mob, "MobID");
            if (mobID != null)
                mobByMobID.put(mobID, mob);
        }

        // Collect all MobIDs which are referenced by SourceClips: a composition mob is
        // a mob which is not referenced itself (the root of the reference chain).
        // SourceMobs (with an EssenceDescription) reference themselves with MobID 0.
        final Set<String> referencedMobIDs = new HashSet<> ();
        for (final AafObj mob : allObjects)
            if (mob.get("Slots") != null)
                collectSourceReferences(mob, referencedMobIDs);

        if (debugListener != null)
        {
            debugListener.accept ("mobs with Slots: " + allObjects.stream ().filter (m -> m.get ("Slots") != null).count ());
            debugListener.accept ("referencedMobIDs: " + referencedMobIDs.size ());
        }

        for (final AafObj mob : allObjects)
        {
            final String ownMobID = getMobID(mob, "MobID");
            // Skip SourceMobs which are directly referenced by SourceClips
            if (ownMobID != null && referencedMobIDs.contains(ownMobID))
                continue;

            final Prop slotsProp = mob.get ("Slots");
            if (slotsProp == null || slotsProp.form != FORM_STRONG_VECTOR)
                continue;

            final String slotsDirName = decodeUtf16(slotsProp.data);
            if (slotsDirName == null || slotsDirName.isEmpty ())
                continue;

            for (final AafObj slot : orderedChildren(mob, slotsDirName))
            {
                final double editRate = findEditRate(slot);
                final String segmentName = strongRefName(slot, "Segment");
                if (segmentName == null)
                    continue;
                final AafObj segment = slot.childrenByName.get(segmentName);
                if (segment == null)
                    continue;

                // The slot name is the best track name (e.g. "Audio 1"), then the name
                // of the mob (the composition), then a generated name
                String trackName = findName(slot);
                if (trackName.isEmpty ())
                    trackName = findName(mob);

                // Collect the clips of the slot: OperationGroups with multiple input
                // segments (stereo or multichannel audio) produce clips with a channel
                // index which are split into separate tracks
                final Track slotTrack = new Track ();
                slotTrack.editRateHz = editRate;
                slotTrack.name = trackName;
                processSegment(segment, editRate, slotTrack, 0, 0, audioByMobID, mobByMobID);

                if (slotTrack.clips.isEmpty ())
                    continue;

                int maxChannel = 0;
                for (final ClipEntry entry: slotTrack.clips)
                    maxChannel = Math.max (maxChannel, entry.channel);

                for (int channel = 0; channel <= maxChannel; channel++)
                {
                    final Track track = new Track ();
                    track.editRateHz = editRate;
                    if (maxChannel == 0)
                        track.name = trackName.isEmpty () ? "Track " + (project.tracks.size () + 1) : trackName;
                    else if (maxChannel == 1)
                        track.name = (trackName.isEmpty () ? "Track " + (project.tracks.size () + 1) : trackName) + (channel == 0 ? " L" : " R");
                    else
                        track.name = (trackName.isEmpty () ? "Track " + (project.tracks.size () + 1) : trackName) + " " + (channel + 1);

                    for (final ClipEntry entry: slotTrack.clips)
                        if (entry.channel == channel)
                            track.clips.add (entry);

                    if (!track.clips.isEmpty ())
                        project.tracks.add (track);
                }
            }
        }

        // Fallback: if no tracks could be reconstructed, at least report the total duration
        for (final Track track : project.tracks)
            for (final ClipEntry entry : track.clips)
                project.totalDurationSeconds = Math.max(project.totalDurationSeconds, entry.startSeconds + entry.durationSeconds);
    }


    /**
     * Processes a segment (Sequence, SourceClip, OperationGroup, ...) and adds all
     * SourceClips found in it to the track, positioned relative to baseOffset.
     */
    private static void processSegment(final AafObj segment, final double editRate, final Track track, final long baseOffset, final int channel, final Map<String, List<AudioClip>> audioByMobID, final Map<String, AafObj> mobByMobID)
    {
        final String componentsDirName = strongVectorName(segment, "Components");
        if (componentsDirName != null)
        {
            // A sequence: the position of each component is the accumulated length of
            // all previous components (including fillers)
            long offset = baseOffset;
            for (final AafObj component : orderedChildren(segment, componentsDirName))
            {
                final long length = getU64(component, "Length", 0);

                final String sourceMobID = getMobID(component, "SourceID");
                if (sourceMobID != null && !isZeroMobID(sourceMobID))
                {
                    // A SourceClip
                    final ClipEntry entry = new ClipEntry(offset, length, editRate);
                    entry.channel = channel;
                    entry.sourceTrackID = getU32(component, "SourceTrackID", 0);
                    final long[] trimOut = {getI64(component, "StartTime", 0)};
                    entry.sourceMobID = resolveSourceChain(component, sourceMobID, trimOut, mobByMobID);
                    entry.audio = matchAudio(audioByMobID, entry.sourceMobID, entry.sourceTrackID);
                    entry.sourceOffsetSeconds = sourceOffsetSeconds(entry.audio, trimOut[0], editRate);
                    track.clips.add(entry);
                }
                else
                {
                    // OperationGroup (InputSegments) or nested sequence (Components):
                    // the contained clips start at the position of the group
                    final String inputSegmentsName = strongVectorName(component, "InputSegments");
                    if (inputSegmentsName != null)
                    {
                        // An OperationGroup with several input segments is a multichannel
                        // clip: each input segment is one channel
                        int inputChannel = channel;
                        for (final AafObj inputSegment : orderedChildren(component, inputSegmentsName))
                            processSegment(inputSegment, editRate, track, offset, inputChannel++, audioByMobID, mobByMobID);
                    }
                    else if (strongVectorName(component, "Components") != null)
                        processSegment(component, editRate, track, offset, channel, audioByMobID, mobByMobID);
                }

                // Fillers and all other components advance the timeline as well
                offset += length;
            }
            return;
        }

        // The segment itself might be a SourceClip (without a sequence in between)
        final String sourceMobID = getMobID(segment, "SourceID");
        if (sourceMobID != null && !isZeroMobID(sourceMobID))
        {
            final long length = getU64(segment, "Length", 0);
            final ClipEntry entry = new ClipEntry(baseOffset, length, editRate);
            entry.channel = channel;
            entry.sourceTrackID = getU32(segment, "SourceTrackID", 0);
            final long[] trimOut = {getI64(segment, "StartTime", 0)};
            entry.sourceMobID = resolveSourceChain(segment, sourceMobID, trimOut, mobByMobID);
            entry.audio = matchAudio(audioByMobID, entry.sourceMobID, entry.sourceTrackID);
            entry.sourceOffsetSeconds = sourceOffsetSeconds(entry.audio, trimOut[0], editRate);
            track.clips.add(entry);
        }
    }


    /**
     * Collects all non-zero source MobIDs referenced by the SourceClips of a mob's
     * timeline (recursively).
     */
    private static void collectSourceReferences(final AafObj mob, final Set<String> referenced)
    {
        final Prop slotsProp = mob.get("Slots");
        if (slotsProp == null || slotsProp.form != FORM_STRONG_VECTOR)
            return;
        final String slotsDirName = decodeUtf16(slotsProp.data);
        if (slotsDirName == null)
            return;
        for (final AafObj slot : orderedChildren(mob, slotsDirName))
        {
            final String segmentName = strongRefName(slot, "Segment");
            if (segmentName == null)
                continue;
            final AafObj segment = slot.childrenByName.get(segmentName);
            if (segment != null)
                collectSegmentReferences(segment, referenced);
        }
    }


    /**
     * Collects all non-zero source MobIDs referenced by a segment (recursively).
     */
    private static void collectSegmentReferences(final AafObj segment, final Set<String> referenced)
    {
        final String componentsDirName = strongVectorName(segment, "Components");
        if (componentsDirName != null)
        {
            for (final AafObj component : orderedChildren(segment, componentsDirName))
                collectSegmentReferences(component, referenced);
            return;
        }

        final String inputSegmentsName = strongVectorName(segment, "InputSegments");
        if (inputSegmentsName != null)
        {
            for (final AafObj inputSegment : orderedChildren(segment, inputSegmentsName))
                collectSegmentReferences(inputSegment, referenced);
            return;
        }

        final String sourceMobID = getMobID(segment, "SourceID");
        if (sourceMobID != null && !isZeroMobID(sourceMobID))
            referenced.add(sourceMobID);
    }


    /**
     * Follows the source reference chain of a clip: a SourceClip might reference an
     * intermediate mob (e.g. a master mob) whose timeline again consists of a SourceClip
     * referencing the actual media source mob. The trim offsets of the chain are
     * accumulated.
     */
    private static String resolveSourceChain(final AafObj clip, final String sourceMobID, final long[] trimInOut, final Map<String, AafObj> mobByMobID)
    {
        String mobID = sourceMobID;
        AafObj referencedMob = mobByMobID.get(mobID);

        int guard = 0;
        while(referencedMob != null && !hasEssenceDescription(referencedMob) && guard++ < 8)
        {
            // Find the source clip of the referenced mob's first timeline slot
            final AafObj nextClip = firstSourceClip(referencedMob);
            if (nextClip == null)
                break;

            final String nextMobID = getMobID(nextClip, "SourceID");
            if (nextMobID == null || isZeroMobID(nextMobID))
                break;

            trimInOut[0] += getI64(nextClip, "StartTime", 0);
            mobID = nextMobID;
            referencedMob = mobByMobID.get(mobID);
        }

        return mobID;
    }


    /**
     * Returns the SourceClip of the first timeline slot of a mob (if any).
     */
    private static AafObj firstSourceClip(final AafObj mob)
    {
        final Prop slotsProp = mob.get("Slots");
        if (slotsProp == null || slotsProp.form != FORM_STRONG_VECTOR)
            return null;
        final String slotsDirName = decodeUtf16(slotsProp.data);
        if (slotsDirName == null)
            return null;

        for (final AafObj slot : orderedChildren(mob, slotsDirName))
        {
            final String segmentName = strongRefName(slot, "Segment");
            if (segmentName == null)
                continue;
            final AafObj segment = slot.childrenByName.get(segmentName);
            if (segment == null)
                continue;

            if (getMobID(segment, "SourceID") != null)
                return segment;

            // A sequence: return the first SourceClip found in it
            final String componentsDirName = strongVectorName(segment, "Components");
            if (componentsDirName == null)
                continue;
            for (final AafObj component : orderedChildren(segment, componentsDirName))
                if (getMobID(component, "SourceID") != null)
                    return component;
        }
        return null;
    }


    /**
     * Checks whether a mob has a real essence description (which source mobs with media
     * have). Note: master mobs may carry an empty EssenceDescription object without any
     * property, which must not stop the source reference chain.
     */
    private static boolean hasEssenceDescription(final AafObj mob)
    {
        for (final AafObj child : mob.childrenByName.values ())
            if (child.name.startsWith ("EssenceDescription") && !child.props.isEmpty ())
                return true;
        return false;
    }


    /**
     * Checks whether a MobID hex string consists of zeros only.
     */
    private static boolean isZeroMobID(final String mobID)
    {
        for (int i = 0; i < mobID.length (); i++)
            if (mobID.charAt (i) != '0')
                return false;
        return true;
    }


    /**
     * Matches an extracted audio file to a clip.
     */
    private static AudioClip matchAudio(final Map<String, List<AudioClip>> audioByMobID, final String sourceMobID, final int sourceTrackID)
    {
        final List<AudioClip> clips = audioByMobID.get (sourceMobID);
        if (clips == null || clips.isEmpty ())
            return null;

        // Multi-channel sources: the source track id selects the channel
        int index = sourceTrackID > 0 ? sourceTrackID - 1 : 0;
        if (index >= clips.size ())
            index = 0;
        final AudioClip audio = clips.get (index);
        audio.used = true;
        return audio;
    }


    /**
     * Converts a SourceClip StartTime (trim) into seconds using the sample rate of the
     * matched audio file.
     */
    private static double sourceOffsetSeconds(final AudioClip audio, final long startTime, final double fallbackRate)
    {
        final long sampleRate = audio == null ? 0 : getWaveSampleRate(audio.audioFile);
        final double rate = sampleRate > 0 ? sampleRate : fallbackRate;
        return rate > 0 ? (double) startTime / rate : 0;
    }


    /**
     * Returns the children of a strong reference vector in their correct (timeline)
     * order. The order is stored in the "&lt;vectorName&gt; index" stream of the owner.
     */
    private static List<AafObj> orderedChildren(final AafObj owner, final String vectorName)
    {
        final List<AafObj> result = new ArrayList<> ();

        // Collect the children belonging to the vector: "<vectorName>{<key>}"
        final Map<Integer, AafObj> childrenByKey = new HashMap<> ();
        final String prefix = vectorName + "{";
        for (final AafObj child : owner.childrenByName.values ())
        {
            if (!child.name.startsWith(prefix) || !child.name.endsWith("}"))
                continue;
            final String keyText = child.name.substring(prefix.length(), child.name.length()-1);
            try
            {
                childrenByKey.put(Integer.valueOf((int)Long.parseLong(keyText, 16)), child);
            }
            catch (final NumberFormatException ex)
            {
                // Ignore malformed names
            }
        }

        if (childrenByKey.isEmpty ())
            return result;

        final byte[] indexData = owner.indexStreams.get(vectorName);
        if (indexData != null && indexData.length >= 12)
        {
            final int count = (int)readUInt32LE(indexData, 0);
            // Strong reference vector index: 12 byte header + 4 byte keys.
            // Set index: 12 byte header + 3 bytes key descriptor + 40 byte entries
            // (4 bytes key, 4 bytes marker, 32 bytes MobID).
            if (indexData.length == 12 + count * 4)
            {
                for (int i = 0; i < count; i++)
                {
                    final AafObj child = childrenByKey.get(Integer.valueOf((int)readUInt32LE(indexData, 12 + i * 4)));
                    if (child != null)
                        result.add(child);
                }
            }
            else if (indexData.length == 15 + count * 40)
            {
                for (int i = 0; i < count; i++)
                {
                    final AafObj child = childrenByKey.get(Integer.valueOf((int)readUInt32LE(indexData, 15 + i * 40)));
                    if (child != null)
                        result.add(child);
                }
            }
        }

        // Append children which are not covered by the index (sorted by key)
        if (result.size() < childrenByKey.size())
        {
            final List<Integer> remainingKeys = new ArrayList<>(childrenByKey.keySet());
            remainingKeys.removeAll(result.stream().map(c -> keyOf(c, vectorName)).toList());
            remainingKeys.sort(Integer::compare);
            for (final Integer key : remainingKeys)
                result.add(childrenByKey.get(key));
        }

        return result;
    }


    /**
     * Extracts the vector key from the name of a child object.
     */
    private static int keyOf(final AafObj child, final String vectorName)
    {
        final String prefix = vectorName + "{";
        if (!child.name.startsWith(prefix) || !child.name.endsWith("}"))
            return -1;
        try
        {
            return (int)Long.parseLong(child.name.substring(prefix.length(), child.name.length()-1), 16);
        }
        catch (final NumberFormatException ex)
        {
            return -1;
        }
    }


    /**
     * Reads the value of a strong reference property (the UTF-16 name of the referenced
     * storage).
     */
    private static String strongRefName(final AafObj object, final String propertyName)
    {
        final Prop prop = object.get(propertyName);
        if (prop == null || prop.form != FORM_STRONG_REF && prop.form != FORM_STRONG_VECTOR)
            return null;
        return decodeUtf16(prop.data);
    }


    /**
     * Reads the value of a strong reference vector property (the UTF-16 name of the
     * vector storage).
     */
    private static String strongVectorName(final AafObj object, final String propertyName)
    {
        final Prop prop = object.get(propertyName);
        if (prop == null || prop.form != FORM_STRONG_VECTOR)
            return null;
        return decodeUtf16(prop.data);
    }


    /**
     * Reads an unsigned 64 bit integer property.
     */
    private static long getU64(final AafObj object, final String propertyName, final long fallback)
    {
        final Prop prop = object.get(propertyName);
        if (prop == null || prop.data.length < 8)
            return fallback;
        return readInt64LE(prop.data, 0);
    }


    /**
     * Reads a signed 64 bit integer property.
     */
    private static long getI64(final AafObj object, final String propertyName, final long fallback)
    {
        return getU64(object, propertyName, fallback);
    }


    /**
     * Reads an unsigned 32 bit integer property.
     */
    private static int getU32(final AafObj object, final String propertyName, final int fallback)
    {
        final Prop prop = object.get(propertyName);
        if (prop == null || prop.data.length < 4)
            return fallback;
        return (int)readUInt32LE(prop.data, 0);
    }


    /**
     * Debug helper: describes the named properties of an object.
     */
    private static String describeProps (final AafObj object)
    {
        final StringBuilder sb = new StringBuilder ();
        for (final Map.Entry<String, Prop> entry: object.namedProps.entrySet ())
        {
            final Prop p = entry.getValue ();
            sb.append (entry.getKey ()).append ('(').append (Integer.toHexString (p.form)).append (',').append (p.data.length).append (')');
            if (p.data.length <= 40 && p.form == FORM_DATA)
            {
                sb.append ('=');
                for (int i = 0; i < Math.min (p.data.length, 24); i++)
                    sb.append (String.format ("%02x", Integer.valueOf (p.data [i])));
            }
            else if (p.form == FORM_STRONG_REF || p.form == FORM_STRONG_VECTOR)
            {
                final String name = decodeUtf16 (p.data);
                if (name != null)
                    sb.append ("='").append (name).append ("'");
            }
            sb.append (" ");
        }
        return sb.toString ();
    }


    /**
     * Reads a 32 byte MobID property and returns it as a hex string.
     */
    private static String getMobID(final AafObj object, final String propertyName)
    {
        final Prop prop = object.get(propertyName);
        if (prop == null || prop.data.length != 32)
            return null;
        return toHex(prop.data);
    }


    /**
     * Reads a rational property (2 x 32 bit: numerator and denominator).
     */
    private static double getRational(final AafObj object, final String propertyName, final double fallback)
    {
        final Prop prop = object.get(propertyName);
        if (prop == null || prop.data.length < 8)
            return fallback;
        final long numerator = readUInt32LE(prop.data, 0);
        final long denominator = readUInt32LE(prop.data, 4);
        if (denominator <= 0)
            return fallback;
        return (double)numerator / (double)denominator;
    }


    /**
     * Finds the edit rate of a timeline slot.
     */
    private static double findEditRate(final AafObj slot)
    {
        final double rate = getRational(slot, "EditRate", 0);
        if (rate > 0)
            return rate;

        // Fallback: search all 8 byte data properties for a plausible edit rate
        for (final Prop prop : slot.props.values())
        {
            if (prop.form != FORM_DATA || prop.data.length < 8)
                continue;
            final long numerator = readUInt32LE(prop.data, 0);
            final long denominator = readUInt32LE(prop.data, 4);
            if (denominator <= 0 || numerator <= 0)
                continue;
            final double value = (double)numerator / (double)denominator;
            for (final double typical : TYPICAL_EDIT_RATES)
                if (Math.abs(value - typical) < 0.01)
                    return value;
        }
        return 48000;
    }


    /**
     * Finds the name of an object (a UTF-16 string property named "Name" or, for
     * timeline slots, "SlotName").
     */
    private static String findName(final AafObj object)
    {
        for (final String propertyName: new String []
        {
            "Name", "SlotName"
        })
        {
            final Prop prop = object.get(propertyName);
            if (prop == null)
                continue;
            final String name = decodeUtf16(prop.data);
            if (name != null && !name.isEmpty ())
                return name;
        }
        return "";
    }


    /**
     * Reads a complete OLE2 stream into a byte array.
     */
    private static byte[] readStream(final DocumentEntry document) throws IOException
    {
        final byte[] data = new byte[(int)Math.min(document.getSize(), Integer.MAX_VALUE)];
        try (DocumentInputStream input = new DocumentInputStream(document))
        {
            int offset = 0;
            while(offset < data.length)
            {
                final int read = input.read(data, offset, data.length - offset);
                if(read < 0)
                    break;
                offset += read;
            }
        }
        return data;
    }


    /**
     * Checks whether an OLE2 stream contains RIFF/WAVE audio data.
     */
    private static boolean isWaveStream(final DocumentEntry document) throws IOException
    {
        if (document.getSize() < 12)
            return false;
        final byte[] header = new byte[12];
        try (DocumentInputStream input = new DocumentInputStream(document))
        {
            if(input.read(header) != 12)
                return false;
        }
        return header [0] == 'R' && header [1] == 'I' && header [2] == 'F' && header [3] == 'F' && header [8] == 'W' && header [9] == 'A' && header [10] == 'V' && header [11] == 'E';
    }


    /**
     * Extracts a WAVE essence stream to a file.
     */
    private static File extractWaveFile(final DocumentEntry document, final File outputFolder, final int counter) throws IOException
    {
        final String fileName = String.format("aaf-audio-%03d.wav", Integer.valueOf(counter));
        final File outputFile = new File(outputFolder, fileName);
        try (InputStream input = new DocumentInputStream(document); OutputStream output = Files.newOutputStream(outputFile.toPath()))
        {
            final byte[] buffer = new byte[8192];
            int read;
            while((read = input.read(buffer)) != -1)
                output.write(buffer, 0, read);
        }
        return outputFile;
    }


    /**
     * Reads the duration of a WAVE file in seconds.
     */
    private static double getWaveDuration(final File waveFile)
    {
        final long[] info = readWaveInfo(waveFile);
        if(info[0] > 0 && info[1] > 0 && info[2] > 0)
            return info [2] / (double)(info[0] * info[1]);
        return 0;
    }


    /**
     * Reads the sample rate of a WAVE file.
     */
    private static long getWaveSampleRate(final File waveFile)
    {
        return readWaveInfo(waveFile)[0];
    }


    /**
     * Reads sample rate, block align and data length of a WAVE file.
     */
    private static long[] readWaveInfo(final File waveFile)
    {
        final long[] result = { 0, 0, 0 };

        try (InputStream input = Files.newInputStream(waveFile.toPath()))
        {
            final byte[] riffHeader = new byte[12];
            if(input.read(riffHeader) != 12)
                return result;

            while(true)
            {
                final byte[] chunkHeader = new byte[8];
                final int read = input.read(chunkHeader);
                if(read != 8)
                    break;

                final String chunkId = new String(chunkHeader, 0, 4, StandardCharsets.US_ASCII);
                final long chunkSize = readUInt32LE(chunkHeader, 4);

                if ("fmt ".equals(chunkId))
                {
                    final byte[] format = new byte[16];
                    if(input.read(format) != 16)
                        break;
                    result [0] = readUInt32LE(format, 4);
                    result [1] = (format [12] & 0xFFL) | (format [13] & 0xFFL) << 8;
                    if(chunkSize > 16)
                        skipFully(input, chunkSize - 16);
                }
                else if ("data".equals(chunkId))
                {
                    result [2] = chunkSize;
                    break;
                }
                else
                {
                    skipFully(input, chunkSize + (chunkSize & 1));
                }
            }
        }
        catch(final IOException ex)
        {
            // Fall through and return what has been read so far
        }
        return result;
    }


    private static void skipFully(final InputStream input, long count) throws IOException
    {
        while(count > 0)
        {
            final long skipped = input.skip(count);
            if(skipped <= 0)
                break;
            count -= skipped;
        }
    }


    // ─────────────────────────────────────
    // Binary decoding helpers
    // ─────────────────────────────────────

    private static int readUInt16LE(final byte[] data, final int offset)
    {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }


    private static long readUInt32LE(final byte[] data, final int offset)
    {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16 | (data[offset + 3] & 0xFFL) << 24;
    }


    private static long readInt64LE(final byte[] data, final int offset)
    {
        long value = 0;
        for(int i = 7; i >= 0; i--)
            value = value << 8 | data[offset + i] & 0xFFL;
        return value;
    }


    private static String toHex(final byte[] data)
    {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for(final byte b : data)
        {
            final String hex = Integer.toHexString(b & 0xFF);
            if(hex.length() == 1)
                sb.append('0');
            sb.append(hex);
        }
        return sb.toString();
    }


    /**
     * Decodes a UTF-16 string (little endian, null terminated).
     */
    private static String decodeUtf16(final byte[] data)
    {
        if(data == null || data.length < 2 || data.length % 2 != 0)
            return null;

        final StringBuilder sb = new StringBuilder();
        for(int i = 0; i + 1 < data.length; i += 2)
        {
            final int ch = data[i] & 0xFF | (data[i + 1] & 0xFF) << 8;
            if(ch == 0)
                break;
            sb.append((char)ch);
        }
        return sb.toString();
    }
}