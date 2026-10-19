// Original implementations by Jürgen Moßgraber - mossgrabers.de
// Gemma 4 12b - Gemini 3.x flash vibe coded following Jürgen Moßgraber work as a guideline
// (c) 2021-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.ableton;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.core.TimeUtils;
import de.mossgrabers.projectconverter.vst.Vst3Preset;
import de.mossgrabers.tools.ui.BasicConfig;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser.ExtensionFilter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.Channel;
import com.bitwig.dawproject.MixerRole;
import com.bitwig.dawproject.ContentType;
import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.FileReference;
import com.bitwig.dawproject.BoolParameter;
import com.bitwig.dawproject.Unit;
import com.bitwig.dawproject.device.Device;
import com.bitwig.dawproject.device.DeviceRole;
import com.bitwig.dawproject.device.Vst3Plugin;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Clips;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.Audio;
import com.bitwig.dawproject.timeline.Notes;
import com.bitwig.dawproject.timeline.Note;
import com.bitwig.dawproject.timeline.TimeUnit;
import com.bitwig.dawproject.TimeSignatureParameter;
import com.bitwig.dawproject.RealParameter;
import com.bitwig.dawproject.Application;
import com.bitwig.dawproject.Transport;
import com.bitwig.dawproject.timeline.Points;
import com.bitwig.dawproject.timeline.Markers;

/**
 * Detector for Ableton Live projects (.als).
 *
 * Uses abletoolz to extract the XML representation and then parses it.
 *
 * @author Jurgen Mossgraber
 */
public class AbletonDetector extends AbstractCoreTask implements ISourceFormat
{
    private static final ExtensionFilter EXTENSION_FILTER = new ExtensionFilter ("Ableton Live", "*.als");

    public AbletonDetector (final INotifier notifier)
    {
        super ("Ableton", notifier);
    }

    @Override
    public Node getEditPane ()
    {
        final VBox panel = new VBox (10);
        panel.getChildren ().add (new Label ("Reads Ableton Live projects (.als) using abletoolz."));
        return panel;
    }

    @Override
    public void saveSettings (final BasicConfig config)
    {
        // No settings
    }

    @Override
    public void loadSettings (final BasicConfig config)
    {
        // No settings
    }

    @Override
    public void shutdown ()
    {
        // Nothing to clean up
    }

    @Override
    public void cancel ()
    {
        // Nothing to cancel
    }

    @Override
    public ExtensionFilter getExtensionFilter ()
    {
        return EXTENSION_FILTER;
    }

    @Override
    public DawProjectContainer read (final File sourceFile) throws IOException, ParseException
    {
        System.out.println ("[DEBUG-FLOW] Inizio lettura file .als: " + sourceFile.getAbsolutePath ());
        final ProcessBuilder pb = new ProcessBuilder (
            "python3", "-m", "abletoolz", "-x", sourceFile.getAbsolutePath ()
        );
        pb.inheritIO ();
        final Process process = pb.start ();
        
        int exitCode;
        try {
            exitCode = process.waitFor ();
        } catch (InterruptedException e) {
            Thread.currentThread ().interrupt ();
            throw new IOException ("Process interrupted", e);
        }

        if (exitCode != 0)
        {
            throw new IOException ("abletoolz failed with exit code " + exitCode);
        }

        String baseName = getNameWithoutExtension (sourceFile);
        File xmlFile = new File (sourceFile.getParentFile (), baseName + ".xml");

        if (!xmlFile.exists ())
        {
            final File fallbackXml = new File (sourceFile.getParentFile (), sourceFile.getName () + ".xml");
            if (fallbackXml.exists ())
                xmlFile = fallbackXml;
            else
                throw new IOException ("Expected XML file was not created. Checked: " + xmlFile.getAbsolutePath ());
        }

        System.out.println ("[DEBUG-FLOW] File XML individuato: " + xmlFile.getAbsolutePath ());

        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance ();
        final Document builder;
        try {
            builder = factory.newDocumentBuilder ().parse (xmlFile);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException ("Error parsing XML content", e);
        }

        return parseAbletonXml (builder, sourceFile);
    }

    private String getNameWithoutExtension (final File file)
    {
        String name = file.getName ();
        if (name.endsWith (".als"))
            name = name.substring (0, name.length () - 4);
        return name;
    }

    private DawProjectContainer parseAbletonXml (final Document doc, final File sourceFile) throws IOException
    {
        final Element root = doc.getDocumentElement ();

        // 1. Ricerca del BPM globale
        double tempo = 120.0;
        final NodeList tempoNodes = root.getElementsByTagName ("Tempo");
        for (int i = 0; i < tempoNodes.getLength (); i++)
        {
            Element tempoElem = (Element) tempoNodes.item (i);
            Element manualElem = (Element) tempoElem.getElementsByTagName ("Manual").item (0);
            if (manualElem != null && manualElem.hasAttribute ("Value"))
            {
                double val = Double.parseDouble (manualElem.getAttribute ("Value"));
                if (val >= 20.0 && val <= 999.0)
                {
                    tempo = val;
                    break;
                }
            }
        }
        System.out.println ("[DEBUG-FLOW] BPM Rilevato: " + tempo);

        final DawProjectContainer container = new DawProjectContainer (getNameWithoutExtension (sourceFile), new AbletonMediaFiles (sourceFile));
        final Project project = container.getProject ();
        final AbletonMediaFiles mediaFiles = (AbletonMediaFiles) container.getMediaFiles ();
        
        project.application = new Application ();
        project.application.name = "Ableton Live";
        project.application.version = "1.0";

        project.transport = new Transport ();
        project.transport.tempo = new RealParameter ();
        project.transport.tempo.value = Double.valueOf (tempo);
        
        project.transport.timeSignature = new TimeSignatureParameter ();
        project.transport.timeSignature.numerator = Integer.valueOf (4);
        project.transport.timeSignature.denominator = Integer.valueOf (4);

        project.arrangement = new Arrangement ();
        project.arrangement.lanes = new Lanes ();
        TimeUtils.setTimeUnit (project.arrangement.lanes, false);
        
        project.arrangement.timeSignatureAutomation = new Points ();
        project.arrangement.tempoAutomation = new Points ();
        project.arrangement.markers = new Markers ();

        // 2. Recupero nodi traccia effettivi
        final List<Element> trackElements = new ArrayList<> ();
        final NodeList tracksList = root.getElementsByTagName ("Tracks");
        if (tracksList.getLength () > 0)
        {
            Element tracksElem = (Element) tracksList.item (0);
            org.w3c.dom.Node child = tracksElem.getFirstChild ();
            while (child != null)
            {
                if (child.getNodeType () == org.w3c.dom.Node.ELEMENT_NODE)
                {
                    String nodeName = child.getNodeName ();
                    if ("AudioTrack".equals (nodeName) || "MidiTrack".equals (nodeName) || "GroupTrack".equals (nodeName))
                    {
                        trackElements.add ((Element) child);
                    }
                }
                child = child.getNextSibling ();
            }
        }

        System.out.println ("[DEBUG-FLOW] Tracce totali (incluse GroupTrack): " + trackElements.size ());

        // Mappe per la gerarchia Parent-Child
        final Map<String, Track> idToTrackMap = new HashMap<> ();
        final Map<String, Lanes> idToLaneMap = new HashMap<> ();
        final Map<Track, String> trackToParentIdMap = new HashMap<> ();
        final List<Track> orderedTracks = new ArrayList<> ();

        int trackIndex = 0;
        for (final Element trackElem : trackElements)
        {
            trackIndex++;
            final String nodeName = trackElem.getNodeName ();
            final boolean isMidi = "MidiTrack".equals (nodeName);
            final boolean isGroup = "GroupTrack".equals (nodeName);

            final String trackId = trackElem.hasAttribute ("Id") ? trackElem.getAttribute ("Id") : String.valueOf (trackIndex);

            final Track track = new Track ();
            if (isGroup)
            {
                // In DAWproject/Reaper, una summing folder ha contentType = TRACKS
                track.contentType = new ContentType[] { ContentType.TRACKS };
                track.tracks = new ArrayList<> ();
            }
            else
            {
                track.contentType = isMidi ? new ContentType[] { ContentType.NOTES } : new ContentType[] { ContentType.AUDIO };
            }

            // Nome della traccia
            String defaultPrefix = isGroup ? "Group " : (isMidi ? "MIDI " : "Audio ");
            String trackName = defaultPrefix + trackIndex;
            final NodeList nameList = trackElem.getElementsByTagName ("Name");
            if (nameList.getLength () > 0)
            {
                Element nameElem = (Element) nameList.item (0);
                Element effName = (Element) nameElem.getElementsByTagName ("EffectiveName").item (0);
                if (effName != null && effName.hasAttribute ("Value") && !effName.getAttribute ("Value").isBlank ())
                    trackName = effName.getAttribute ("Value");
            }
            track.name = trackName;

            // Creazione Channel e Mixer
            track.channel = new Channel ();
            track.channel.name = trackName;
            track.channel.role = isGroup ? MixerRole.MASTER : MixerRole.REGULAR;
            track.channel.audioChannels = Integer.valueOf (2);
            track.channel.devices = new ArrayList<> ();

            parseTrackMixer (trackElem, track.channel, trackName);

            // Creazione della Lane di Arrangiamento
            final Lanes trackLane = new Lanes ();
            trackLane.track = track;
            trackLane.timeUnit = TimeUnit.BEATS;
            trackLane.lanes = new ArrayList<> ();

            final Clips trackClips = new Clips ();
            trackClips.timeUnit = TimeUnit.BEATS;
            trackLane.lanes.add (trackClips);

            // Parsing Plugins / VST3
            parseTrackDevices (trackElem, track, trackIndex, mediaFiles);

            if (isMidi)
            {
                parseMidiClips (trackElem, trackClips, trackName);
            }
            else if (!isGroup)
            {
                parseAudioClips (trackElem, trackClips, trackName, tempo, sourceFile, mediaFiles, container);
            }

            // Mappatura Id e ParentGroupId
            idToTrackMap.put (trackId, track);
            idToLaneMap.put (trackId, trackLane);
            orderedTracks.add (track);

            String parentGroupId = "-1";
            final NodeList groupNodes = trackElem.getElementsByTagName ("TrackGroupId");
            if (groupNodes.getLength () > 0)
            {
                Element grpElem = (Element) groupNodes.item (0);
                if (grpElem.hasAttribute ("Value"))
                    parentGroupId = grpElem.getAttribute ("Value");
            }
            trackToParentIdMap.put (track, parentGroupId);
        }

        // Assemblaggio della Gerarchia Summing Folders (Parent-Child)
        for (final Track track : orderedTracks)
        {
            final String parentId = trackToParentIdMap.get (track);
            final Lanes trackLane = idToLaneMap.get (getTrackIdByTrack (idToTrackMap, track));

            if (parentId != null && !"-1".equals (parentId) && idToTrackMap.containsKey (parentId))
            {
                final Track parentGroup = idToTrackMap.get (parentId);
                final Lanes parentLane = idToLaneMap.get (parentId);

                parentGroup.tracks.add (track);
                parentLane.lanes.add (trackLane);
                System.out.println ("[DEBUG-FLOW] Traccia '" + track.name + "' inserita nel Gruppo '" + parentGroup.name + "'");
            }
            else
            {
                // Traccia root di livello superiore
                project.structure.add (track);
                project.arrangement.lanes.lanes.add (trackLane);
            }
        }

        return container;
    }

    private static String getTrackIdByTrack (final Map<String, Track> map, final Track target)
    {
        for (Map.Entry<String, Track> entry : map.entrySet ())
        {
            if (entry.getValue () == target)
                return entry.getKey ();
        }
        return "";
    }

    private void parseTrackMixer (final Element trackElem, final Channel channel, final String trackName)
    {
        final NodeList mixerList = trackElem.getElementsByTagName ("Mixer");
        if (mixerList.getLength () == 0)
            return;

        final Element mixerElem = (Element) mixerList.item (0);

        // 1. Volume
        double volumeVal = 1.0;
        final NodeList volumeList = mixerElem.getElementsByTagName ("Volume");
        if (volumeList.getLength () > 0)
        {
            Element volElem = (Element) volumeList.item (0);
            Element manualElem = (Element) volElem.getElementsByTagName ("Manual").item (0);
            if (manualElem != null && manualElem.hasAttribute ("Value"))
                volumeVal = Double.parseDouble (manualElem.getAttribute ("Value"));
        }
        channel.volume = new RealParameter ();
        channel.volume.unit = Unit.LINEAR;
        channel.volume.value = Double.valueOf (volumeVal);

        // 2. Panorama
        double panVal = 0.0;
        final NodeList panList = mixerElem.getElementsByTagName ("Pan");
        if (panList.getLength () > 0)
        {
            Element panElem = (Element) panList.item (0);
            Element manualElem = (Element) panElem.getElementsByTagName ("Manual").item (0);
            if (manualElem != null && manualElem.hasAttribute ("Value"))
                panVal = Double.parseDouble (manualElem.getAttribute ("Value"));
        }
        double normalizedPan = (panVal + 1.0) / 2.0;
        normalizedPan = Math.max (0.0, Math.min (1.0, normalizedPan));
        channel.pan = new RealParameter ();
        channel.pan.unit = Unit.NORMALIZED;
        channel.pan.value = Double.valueOf (normalizedPan);

        // 3. Mute
        boolean isMuted = false;
        final NodeList speakerList = mixerElem.getElementsByTagName ("Speaker");
        if (speakerList.getLength () > 0)
        {
            Element spkElem = (Element) speakerList.item (0);
            Element manualElem = (Element) spkElem.getElementsByTagName ("Manual").item (0);
            if (manualElem != null && manualElem.hasAttribute ("Value"))
            {
                boolean speakerOn = Boolean.parseBoolean (manualElem.getAttribute ("Value"));
                isMuted = !speakerOn;
            }
        }
        channel.mute = new BoolParameter ();
        channel.mute.value = Boolean.valueOf (isMuted);

        // 4. Solo
        boolean isSolo = false;
        final NodeList soloList = mixerElem.getElementsByTagName ("SoloSink");
        if (soloList.getLength () > 0)
        {
            Element soloElem = (Element) soloList.item (0);
            if (soloElem.hasAttribute ("Value"))
                isSolo = Boolean.parseBoolean (soloElem.getAttribute ("Value"));
        }
        channel.solo = Boolean.valueOf (isSolo);

        System.out.println ("[DEBUG-FLOW] Mixer per '" + trackName + "': Vol=" + volumeVal + " Pan=" + panVal + " Mute=" + isMuted + " Solo=" + isSolo);
    }

    private void parseTrackDevices (final Element trackElem, final Track track, final int trackIndex, final AbletonMediaFiles mediaFiles)
    {
        final NodeList pluginDevices = trackElem.getElementsByTagName ("PluginDevice");
        for (int i = 0; i < pluginDevices.getLength (); i++)
        {
            final Element pluginDevElem = (Element) pluginDevices.item (i);
            final NodeList vst3InfoList = pluginDevElem.getElementsByTagName ("Vst3PluginInfo");
            if (vst3InfoList.getLength () == 0)
                continue;

            final Element vst3Info = (Element) vst3InfoList.item (0);

            String pluginName = null;
            final NodeList nameList = vst3Info.getElementsByTagName ("Name");
            for (int n = 0; n < nameList.getLength (); n++)
            {
                Element ne = (Element) nameList.item (n);
                if (ne.hasAttribute ("Value") && !ne.getAttribute ("Value").isBlank ())
                {
                    pluginName = ne.getAttribute ("Value");
                    break;
                }
            }

            if (pluginName == null || pluginName.isBlank ())
            {
                final NodeList userNames = pluginDevElem.getElementsByTagName ("UserName");
                if (userNames.getLength () > 0)
                {
                    Element ue = (Element) userNames.item (0);
                    if (ue.hasAttribute ("Value") && !ue.getAttribute ("Value").isBlank ())
                        pluginName = ue.getAttribute ("Value");
                }
            }

            if (pluginName == null || pluginName.isBlank ())
            {
                final NodeList browserPaths = pluginDevElem.getElementsByTagName ("BrowserContentPath");
                if (browserPaths.getLength () > 0)
                {
                    Element bpe = (Element) browserPaths.item (0);
                    if (bpe.hasAttribute ("Value"))
                    {
                        String bp = bpe.getAttribute ("Value");
                        int hashIdx = bp.indexOf ('#');
                        if (hashIdx >= 0)
                            pluginName = bp.substring (hashIdx + 1).replace ("%20", " ");
                    }
                }
            }

            if (pluginName == null || pluginName.isBlank ())
                pluginName = "VST3 Plugin";

            String deviceId = null;
            final NodeList branchList = pluginDevElem.getElementsByTagName ("BranchDeviceId");
            if (branchList.getLength () > 0)
            {
                Element be = (Element) branchList.item (0);
                if (be.hasAttribute ("Value"))
                {
                    String bv = be.getAttribute ("Value");
                    int lastColon = bv.lastIndexOf (':');
                    if (lastColon >= 0)
                    {
                        String rawId = bv.substring (lastColon + 1).replace ("-", "");
                        if (rawId.length () == 32)
                            deviceId = rawId.toUpperCase ();
                    }
                }
            }

            if (deviceId == null)
            {
                final NodeList uidList = vst3Info.getElementsByTagName ("Uid");
                if (uidList.getLength () > 0)
                {
                    Element uidElem = (Element) uidList.item (0);
                    long f0 = getFieldLong (uidElem, "Fields.0");
                    long f1 = getFieldLong (uidElem, "Fields.1");
                    long f2 = getFieldLong (uidElem, "Fields.2");
                    long f3 = getFieldLong (uidElem, "Fields.3");
                    deviceId = String.format ("%08X%08X%08X%08X", f0, f1, f2, f3);
                }
            }

            if (deviceId == null || deviceId.length () != 32)
                continue;

            int devType = 1;
            final NodeList devTypeList = vst3Info.getElementsByTagName ("DeviceType");
            if (devTypeList.getLength () > 0)
            {
                Element dtElem = (Element) devTypeList.item (0);
                if (dtElem.hasAttribute ("Value"))
                    devType = Integer.parseInt (dtElem.getAttribute ("Value"));
            }
            DeviceRole role = (devType == 1) ? DeviceRole.INSTRUMENT : DeviceRole.AUDIO_FX;

            final List<byte[]> chunks = new ArrayList<> ();
            final NodeList procStateList = pluginDevElem.getElementsByTagName ("ProcessorState");
            if (procStateList.getLength () > 0)
            {
                String hexText = procStateList.item (0).getTextContent ();
                byte[] procBytes = parseHexBinary (hexText);
                if (procBytes.length > 0)
                    chunks.add (procBytes);
            }

            final NodeList ctrlStateList = pluginDevElem.getElementsByTagName ("ControllerState");
            if (ctrlStateList.getLength () > 0)
            {
                String hexText = ctrlStateList.item (0).getTextContent ();
                byte[] ctrlBytes = parseHexBinary (hexText);
                if (ctrlBytes.length > 0)
                    chunks.add (ctrlBytes);
            }

            final Vst3Plugin vst3 = new Vst3Plugin ();
            vst3.deviceName = pluginName;
            vst3.deviceID = deviceId;
            vst3.deviceVendor = "Arturia";
            vst3.deviceRole = role;
            vst3.enabled = new BoolParameter ();
            vst3.enabled.value = Boolean.TRUE;

            if (!chunks.isEmpty ())
            {
                final String statePath = "plugins/track_" + trackIndex + "_plugin_" + (i + 1) + ".vstpreset";
                
                try
                {
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream ();
                    new Vst3Preset ().write (baos, deviceId, chunks);
                    final byte[] vst3PresetBytes = baos.toByteArray ();

                    final Vst3Preset validator = new Vst3Preset ();
                    validator.read (new ByteArrayInputStream (vst3PresetBytes));

                    mediaFiles.addBytes (statePath, vst3PresetBytes);

                    vst3.state = new FileReference ();
                    vst3.state.path = statePath;
                    vst3.state.external = Boolean.FALSE;
                    System.out.println ("[DEBUG-FLOW] VST3 salvato con successo: " + pluginName + " [ID=" + deviceId + "] -> " + statePath + " (" + vst3PresetBytes.length + " bytes)");
                }
                catch (final Exception e)
                {
                    System.out.println ("[DEBUG-WARN] Preset non conforme scartato per evitare crash di Reaper: " + e.getMessage ());
                }
            }

            track.channel.devices.add (vst3);
        }
    }

    private static long getFieldLong (final Element parent, final String tagName)
    {
        final NodeList list = parent.getElementsByTagName (tagName);
        if (list.getLength () > 0)
        {
            Element elem = (Element) list.item (0);
            if (elem.hasAttribute ("Value"))
                return Long.parseLong (elem.getAttribute ("Value"));
        }
        return 0L;
    }

    private static byte[] parseHexBinary (final String hexString)
    {
        if (hexString == null)
            return new byte[0];

        final String clean = hexString.replaceAll ("[^0-9a-fA-F]", "");
        final int len = clean.length ();
        final int evenLen = len - (len % 2);
        final byte[] data = new byte[evenLen / 2];

        for (int i = 0; i < evenLen; i += 2)
        {
            final int h = Character.digit (clean.charAt (i), 16);
            final int l = Character.digit (clean.charAt (i + 1), 16);
            if (h != -1 && l != -1)
                data[i / 2] = (byte) ((h << 4) + l);
        }
        return data;
    }

    private void parseMidiClips (final Element trackElem, final Clips trackClips, final String trackName)
    {
        final NodeList midiClips = trackElem.getElementsByTagName ("MidiClip");
        for (int j = 0; j < midiClips.getLength (); j++)
        {
            final Element clipElem = (Element) midiClips.item (j);

            if (!clipElem.hasAttribute ("Time"))
                continue;

            double timeBeats = Double.parseDouble (clipElem.getAttribute ("Time"));
            if (timeBeats < 0)
                continue;

            double currentStart = timeBeats;
            double currentEnd = timeBeats + 4.0;

            Element startElem = (Element) clipElem.getElementsByTagName ("CurrentStart").item (0);
            if (startElem != null && startElem.hasAttribute ("Value"))
                currentStart = Double.parseDouble (startElem.getAttribute ("Value"));

            Element endElem = (Element) clipElem.getElementsByTagName ("CurrentEnd").item (0);
            if (endElem != null && endElem.hasAttribute ("Value"))
                currentEnd = Double.parseDouble (endElem.getAttribute ("Value"));

            double clipDurationBeats = Math.max (0.001, currentEnd - currentStart);

            double loopStart = 0.0;
            double loopEnd = clipDurationBeats;
            boolean isLoopOn = false;

            final NodeList loopNodes = clipElem.getElementsByTagName ("Loop");
            if (loopNodes.getLength () > 0)
            {
                Element loopElem = (Element) loopNodes.item (0);
                Element loopOnElem = (Element) loopElem.getElementsByTagName ("LoopOn").item (0);
                if (loopOnElem != null && loopOnElem.hasAttribute ("Value"))
                    isLoopOn = Boolean.parseBoolean (loopOnElem.getAttribute ("Value"));

                Element loopStartElem = (Element) loopElem.getElementsByTagName ("LoopStart").item (0);
                if (loopStartElem != null && loopStartElem.hasAttribute ("Value"))
                    loopStart = Double.parseDouble (loopStartElem.getAttribute ("Value"));

                Element loopEndElem = (Element) loopElem.getElementsByTagName ("LoopEnd").item (0);
                if (loopEndElem != null && loopEndElem.hasAttribute ("Value"))
                    loopEnd = Double.parseDouble (loopEndElem.getAttribute ("Value"));
            }

            String clipName = "MIDI Clip " + (j + 1);
            final NodeList nameList = clipElem.getElementsByTagName ("Name");
            if (nameList.getLength () > 0)
            {
                Element nameElem = (Element) nameList.item (0);
                if (nameElem.hasAttribute ("Value"))
                    clipName = nameElem.getAttribute ("Value");
            }

            final Clip clip = new Clip ();
            clip.name = clipName;
            clip.time = currentStart;
            clip.duration = Double.valueOf (clipDurationBeats);
            clip.contentTimeUnit = TimeUnit.BEATS;
            clip.playStart = Double.valueOf (loopStart);
            clip.playStop = Double.valueOf (loopEnd);

            if (isLoopOn)
            {
                clip.loopStart = Double.valueOf (loopStart);
                clip.loopEnd = Double.valueOf (loopEnd);
            }

            final Notes notesContent = new Notes ();
            notesContent.timeUnit = TimeUnit.BEATS;

            final NodeList keyTrackList = clipElem.getElementsByTagName ("KeyTrack");
            if (keyTrackList.getLength () > 0)
            {
                for (int k = 0; k < keyTrackList.getLength (); k++)
                {
                    final Element keyTrackElem = (Element) keyTrackList.item (k);
                    int midiKey = 60;
                    Element midiKeyElem = (Element) keyTrackElem.getElementsByTagName ("MidiKey").item (0);
                    if (midiKeyElem != null && midiKeyElem.hasAttribute ("Value"))
                        midiKey = Integer.parseInt (midiKeyElem.getAttribute ("Value"));

                    final NodeList noteEvents = keyTrackElem.getElementsByTagName ("MidiNoteEvent");
                    for (int n = 0; n < noteEvents.getLength (); n++)
                    {
                        final Element noteEvent = (Element) noteEvents.item (n);
                        addMidiNote (notesContent, noteEvent, midiKey);
                    }
                }
            }
            else
            {
                final NodeList noteEvents = clipElem.getElementsByTagName ("MidiNoteEvent");
                for (int n = 0; n < noteEvents.getLength (); n++)
                {
                    final Element noteEvent = (Element) noteEvents.item (n);
                    int midiKey = 60;
                    if (noteEvent.hasAttribute ("Key"))
                        midiKey = Integer.parseInt (noteEvent.getAttribute ("Key"));
                    addMidiNote (notesContent, noteEvent, midiKey);
                }
            }

            clip.content = notesContent;
            trackClips.clips.add (clip);
            System.out.println ("[DEBUG-FLOW] Clip MIDI aggiunta a traccia " + trackName + ": " + clipName + " time=" + currentStart + " dur=" + clipDurationBeats + " noteCount=" + notesContent.notes.size ());
        }
    }

    private void addMidiNote (final Notes notesContent, final Element noteEvent, final int defaultKey)
    {
        double noteTime = 0.0;
        if (noteEvent.hasAttribute ("Time"))
            noteTime = Double.parseDouble (noteEvent.getAttribute ("Time"));

        double noteDuration = 1.0;
        if (noteEvent.hasAttribute ("Duration"))
            noteDuration = Double.parseDouble (noteEvent.getAttribute ("Duration"));

        double velocity = 100.0;
        if (noteEvent.hasAttribute ("Velocity"))
            velocity = Double.parseDouble (noteEvent.getAttribute ("Velocity"));

        double offVelocity = 64.0;
        if (noteEvent.hasAttribute ("OffVelocity"))
            offVelocity = Double.parseDouble (noteEvent.getAttribute ("OffVelocity"));

        boolean isEnabled = true;
        if (noteEvent.hasAttribute ("IsEnabled"))
            isEnabled = Boolean.parseBoolean (noteEvent.getAttribute ("IsEnabled"));

        if (!isEnabled)
            return;

        final Note note = new Note ();
        note.time = Double.valueOf (noteTime);
        note.duration = Double.valueOf (noteDuration);
        note.key = Integer.valueOf (defaultKey);
        note.channel = Integer.valueOf (0);
        note.velocity = Double.valueOf (velocity / 127.0);
        note.releaseVelocity = Double.valueOf (offVelocity / 127.0);

        notesContent.notes.add (note);
    }

    private void parseAudioClips (final Element trackElem, final Clips trackClips, final String trackName, final double tempo, final File sourceFile, final AbletonMediaFiles mediaFiles, final DawProjectContainer container)
    {
        final NodeList audioClips = trackElem.getElementsByTagName ("AudioClip");
        for (int j = 0; j < audioClips.getLength (); j++)
        {
            final Element clipElem = (Element) audioClips.item (j);

            if (!clipElem.hasAttribute ("Time"))
                continue;

            double timeBeats = Double.parseDouble (clipElem.getAttribute ("Time"));
            if (timeBeats < 0)
                continue;

            double currentStart = timeBeats;
            double currentEnd = timeBeats;

            Element startElem = (Element) clipElem.getElementsByTagName ("CurrentStart").item (0);
            if (startElem != null && startElem.hasAttribute ("Value"))
                currentStart = Double.parseDouble (startElem.getAttribute ("Value"));

            Element endElem = (Element) clipElem.getElementsByTagName ("CurrentEnd").item (0);
            if (endElem != null && endElem.hasAttribute ("Value"))
                currentEnd = Double.parseDouble (endElem.getAttribute ("Value"));

            double clipDurationBeats = Math.max (0.001, currentEnd - currentStart);

            double loopStart = 0.0;
            double outMarkerSeconds = clipDurationBeats * (60.0 / tempo);
            boolean isLoopOn = false;

            final NodeList loopNodes = clipElem.getElementsByTagName ("Loop");
            if (loopNodes.getLength () > 0)
            {
                Element loopElem = (Element) loopNodes.item (0);
                Element loopOnElem = (Element) loopElem.getElementsByTagName ("LoopOn").item (0);
                if (loopOnElem != null && loopOnElem.hasAttribute ("Value"))
                    isLoopOn = Boolean.parseBoolean (loopOnElem.getAttribute ("Value"));

                Element loopStartElem = (Element) loopElem.getElementsByTagName ("LoopStart").item (0);
                if (loopStartElem != null && loopStartElem.hasAttribute ("Value"))
                    loopStart = Double.parseDouble (loopStartElem.getAttribute ("Value"));

                Element outMarkerElem = (Element) loopElem.getElementsByTagName ("OutMarker").item (0);
                if (outMarkerElem != null && outMarkerElem.hasAttribute ("Value"))
                    outMarkerSeconds = Double.parseDouble (outMarkerElem.getAttribute ("Value"));
            }

            int sampleRate = 44100;
            String mediaID = null;
            final NodeList sampleRefs = clipElem.getElementsByTagName ("SampleRef");
            if (sampleRefs.getLength () > 0)
            {
                Element sampleRef = (Element) sampleRefs.item (0);
                Element srElem = (Element) sampleRef.getElementsByTagName ("DefaultSampleRate").item (0);
                if (srElem != null && srElem.hasAttribute ("Value"))
                    sampleRate = Integer.parseInt (srElem.getAttribute ("Value"));

                Element fileRef = (Element) sampleRef.getElementsByTagName ("FileRef").item (0);
                if (fileRef != null)
                {
                    Element pathElem = (Element) fileRef.getElementsByTagName ("Path").item (0);
                    if (pathElem != null && pathElem.hasAttribute ("Value"))
                    {
                        String path = pathElem.getAttribute ("Value");
                        File mediaFile = mediaFiles.resolvePath (path, sourceFile);
                        if (mediaFile != null)
                        {
                            mediaID = "samples/" + mediaFile.getName ();
                            mediaFiles.add (mediaID, mediaFile);
                            System.out.println ("[DEBUG-FLOW] Media aggiunto: " + mediaID + " -> " + mediaFile.getAbsolutePath ());
                        }
                    }
                }
            }

            final Clip clip = new Clip ();
            clip.name = "Clip " + (j + 1);
            clip.time = currentStart;
            clip.duration = Double.valueOf (clipDurationBeats);
            clip.contentTimeUnit = TimeUnit.SECONDS;
            clip.playStart = Double.valueOf (loopStart);
            clip.playStop = Double.valueOf (outMarkerSeconds);

            if (isLoopOn)
            {
                clip.loopStart = Double.valueOf (loopStart);
                clip.loopEnd = Double.valueOf (outMarkerSeconds);
            }

            final Audio audioContent = new Audio ();
            audioContent.timeUnit = TimeUnit.SECONDS;
            audioContent.sampleRate = sampleRate;
            audioContent.channels = 2;
            audioContent.duration = outMarkerSeconds;

            if (mediaID != null)
            {
                audioContent.file = new FileReference ();
                audioContent.file.path = mediaID;
                audioContent.file.external = Boolean.FALSE;
            }

            clip.content = audioContent;
            trackClips.clips.add (clip);
            System.out.println ("[DEBUG-FLOW] Clip Audio aggiunta a traccia " + trackName + ": time=" + currentStart + " durBeats=" + clipDurationBeats);
        }
    }
}