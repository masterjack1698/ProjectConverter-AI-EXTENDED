package de.mossgrabers.projectconverter.format.ptx;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.*;

import com.bitwig.dawproject.Arrangement;
import com.bitwig.dawproject.Channel;
import com.bitwig.dawproject.ContentType;
import com.bitwig.dawproject.FileReference;
import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.Referenceable;
import com.bitwig.dawproject.Track;
import com.bitwig.dawproject.timeline.Audio;
import com.bitwig.dawproject.timeline.Clip;
import com.bitwig.dawproject.timeline.Clips;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.TimeUnit;

import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.core.DawProjectContainer;
import de.mossgrabers.projectconverter.core.IMediaFiles;
import de.mossgrabers.projectconverter.core.ISourceFormat;
import de.mossgrabers.projectconverter.core.TimeUtils;
import de.mossgrabers.tools.ui.BasicConfig;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser.ExtensionFilter;

import java.nio.file.Files;

/**
 * Detector for Pro Tools .ptx files.
 *
 * It parses the .ptx binary format, extracts sample rate, file catalog,
 * clip definitions, and timeline information to construct a DawProjectContainer.
 *
 * @author Jürgen Moßgraber
 */
public class PtxDetector extends AbstractCoreTask implements ISourceFormat
{
    private static final ExtensionFilter EXTENSION_FILTER = new ExtensionFilter ("Pro Tools Project", "*.ptx");

    public PtxDetector (final INotifier notifier)
    {
        super ("Pro Tools", notifier);
    }

    @Override
    public Node getEditPane ()
    {
        final VBox panel = new VBox (10);
        panel.getChildren ().add (new Label ("Reads Pro Tools .ptx files and converts them to a DAWproject."));
        return panel;
    }

    @Override
    public void saveSettings (final BasicConfig config) {}

    @Override
    public void loadSettings (final BasicConfig config) {}

    @Override
    public void shutdown () {}

    @Override
    public void cancel () {}

    @Override
    public ExtensionFilter getExtensionFilter ()
    {
        return EXTENSION_FILTER;
    }

    @Override
    public DawProjectContainer read (final File sourceFile) throws IOException, ParseException
    {
        Referenceable.setAutoID (true);

        final byte[] raw = Files.readAllBytes(sourceFile.toPath());
        final byte[] data = PtxParser.unxor(raw);
        final List<PtxParser.Block> blocks = PtxParser.walkBlocks(data);

        try (final DawProjectContainer dawProject = new DawProjectContainer (getNameWithoutExtension (sourceFile), new PtxMediaFiles ()))
        {
            final Project project = dawProject.getProject ();
            project.application.name = "Pro Tools (converted with ProjectConverter)";
            project.application.version = "1.0";

            project.arrangement = new Arrangement ();
            project.arrangement.lanes = new Lanes ();
            TimeUtils.setTimeUnit (project.arrangement.lanes, false);

            // 1. Extract Sample Rate
            int sampleRate = 44100;
            for (PtxParser.Block b : blocks) {
                if (b.ctype == 0x1028) {
                    sampleRate = ByteBuffer.wrap(data, b.offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    break;
                }
            }

            // 2. Extract File Catalog
            List<String> fileCatalog = new ArrayList<>();
            for (PtxParser.Block b : blocks) {
                if (b.ctype == 0x1004) {
                    for (PtxParser.Block child : b.children) {
                        if (child.ctype == 0x103a) {
                            fileCatalog.addAll(extractStringsFromBlock(data, child));
                        }
                    }
                    break;
                }
            }

            // 3. Extract Clip Definitions
            List<ClipDef> clipDefs = new ArrayList<>();
            for (PtxParser.Block b : blocks) {
                if (b.ctype == 0x262a) {
                    for (PtxParser.Block d : b.children) {
                        if (d.ctype == 0x2629) {
                            for (PtxParser.Block c28 : d.children) {
                                if (c28.ctype == 0x2628) {
                                    clipDefs.add(parseClipDef(data, d, c28));
                                }
                            }
                        }
                    }
                    break;
                }
            }

            // 4. Build Tracks and capture Clips containers
            List<Track> tracks = new ArrayList<>();
            List<Clips> trackClips = new ArrayList<>();
            for (int i = 0; i < clipDefs.size(); i++) {
                Track track = new Track ();
                track.name = clipDefs.get(i).name;
                track.contentType = new ContentType [] { ContentType.AUDIO };
                track.channel = new Channel ();
                tracks.add (track);

                Lanes trackLanes = new Lanes ();
                trackLanes.track = track;
                project.arrangement.lanes.lanes.add (trackLanes);

                Clips clips = new Clips ();
                trackLanes.lanes.add (clips);
                TimeUtils.setTimeUnit (clips, false);
                trackClips.add(clips);
            }

            // 5. Extract Playlist and populate Clips using the captured containers
            for (PtxParser.Block b : blocks) {
                if (b.ctype == 0x1054) {
                    for (PtxParser.Block pl : b.children) {
                        if (pl.ctype == 0x1052) {
                            for (PtxParser.Block evCont : pl.children) {
                                if (evCont.ctype == 0x1050) {
                                    for (PtxParser.Block ev : evCont.children) {
                                        if (ev.ctype == 0x104f) {
                                            byte[] payload = new byte[ev.bsize - 2];
                                            System.arraycopy(data, ev.getContentStart(), payload, 0, ev.bsize - 2);
                                            
                                            int ordinal = ByteBuffer.wrap(payload, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                                            if (ordinal >= 0 && ordinal < clipDefs.size()) {
                                                ClipDef def = clipDefs.get(ordinal);
                                                
                                                // Get the Clips container for this specific clip index
                                                Clips clipsObj = trackClips.get(ordinal);
                                                if (clipsObj != null) {
                                                    String mediaID = "samples/" + def.fileName;
                                                    if (def.mediaIndex > 0 && def.mediaIndex <= fileCatalog.size()) {
                                                        mediaID = "samples/" + fileCatalog.get(def.mediaIndex - 1);
                                                    }
                                                    
                                                    dawProject.getMediaFiles().add (mediaID, new File(def.fileName));

                                                    Audio audio = new Audio ();
                                                    audio.file = new FileReference ();
                                                    audio.file.path = mediaID;
                                                    audio.file.external = Boolean.FALSE;
                                                    audio.algorithm = "raw";
                                                    audio.duration = Double.valueOf (def.length);
                                                    audio.sampleRate = sampleRate;
                                                    audio.channels = def.channels;

                                                    Clip innerClip = new Clip ();
                                                    innerClip.contentTimeUnit = TimeUnit.SECONDS;
                                                    innerClip.time = Double.valueOf (0);
                                                    innerClip.duration = Double.valueOf (def.length);
                                                    innerClip.content = audio;

                                                    Clips innerClips = new Clips ();
                                                    innerClips.clips.add (innerClip);

                                                    Clip clip = new Clip ();
                                                    clip.name = def.name;
                                                    clip.time = Double.valueOf (ev.pos);
                                                    clip.duration = Double.valueOf (def.length);
                                                    clip.playStart = Double.valueOf (def.srcOffset);
                                                    clip.enable = Boolean.TRUE;
                                                    clip.content = innerClips;

                                                    clipsObj.clips.add (clip);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            }

            project.structure.addAll (tracks);

            return dawProject;
        }
    }

    private String getNameWithoutExtension (final File file) {
        final String name = file.getName ();
        final int pos = name.lastIndexOf ('.');
        return pos < 0 ? name : name.substring (0, pos);
    }

    private List<String> extractStringsFromBlock(byte[] data, PtxParser.Block b) {
        List<String> strings = new ArrayList<>();
        int pos = b.offset + 11;
        int limit = b.getEnd();
        while (pos + 4 <= limit) {
            int len = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (len <= 0 || len > 1 << 20) break;
            String s = new String(data, pos + 4, len, StandardCharsets.UTF_8);
            if (!s.contains(".grp") && !s.contains("Audio Files") && !s.contains("Fade Files")) {
                strings.add(s);
            }
            pos += 4 + len + 1;
        }
        return strings;
    }

    private ClipDef parseClipDef(byte[] data, PtxParser.Block d, PtxParser.Block c28) {
        String name = extractString(data, c28.offset);
        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;
        int flags = ByteBuffer.wrap(data, c28.offset + 4 + nameLen, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
        int selector = data[c28.offset + 4 + nameLen + 2] & 0xFF;
        
        int width = 1;
        if (selector == 0x20) width = 2;
        else if (selector == 0x30) width = 3;
        else if (selector == 0x40) width = 4;

        int lo = c28.offset + 4 + nameLen + 5;
        double length = 0;
        double srcOffset = 0;
        
        int fl = flags & 0xFFF0;
        if (fl == 0x0000 || fl == 0x0001) {
            length = Double.valueOf(ByteBuffer.wrap(data, lo, width).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else if (fl == 0x1001) {
            srcOffset = Double.valueOf(ByteBuffer.wrap(data, lo, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            length = Double.valueOf(ByteBuffer.wrap(data, lo + 4, width).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else if (fl == 0x2000 || fl == 0x2001) {
            srcOffset = Double.valueOf(ByteBuffer.wrap(data, lo, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF);
            length = Double.valueOf(ByteBuffer.wrap(data, lo + 2, width).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else if (fl == 0x3000 || fl == 0x3001) {
            srcOffset = Double.valueOf(ByteBuffer.wrap(data, lo, 3).order(ByteOrder.LITTLE_ENDIAN).getInt());
            length = Double.valueOf(ByteBuffer.wrap(data, lo + 3, width).order(ByteOrder.LITTLE_ENDIAN).getInt());
        } else if (fl == 0x4000 || fl == 0x4001) {
            srcOffset = Double.valueOf(ByteBuffer.wrap(data, lo, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            length = Double.valueOf(ByteBuffer.wrap(data, lo + 4, width).order(ByteOrder.LITTLE_ENDIAN).getInt());
        }

        int mediaIndex = ByteBuffer.wrap(data, c28.offset + 96, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        
        ClipDef def = new ClipDef();
        def.name = name;
        def.length = length;
        def.srcOffset = srcOffset;
        def.mediaIndex = mediaIndex;
        def.channels = 2;
        def.fileName = "unknown_audio_file";
        return def;
    }

    private String extractString(byte[] data, int pos) {
        int len = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len <= 0 || len > 1 << 20) return null;
        return new String(data, pos + 4, len, StandardCharsets.UTF_8);
    }

    private static class ClipDef {
        String name;
        double length;
        double srcOffset;
        int mediaIndex;
        int channels;
        String fileName;
    }
}