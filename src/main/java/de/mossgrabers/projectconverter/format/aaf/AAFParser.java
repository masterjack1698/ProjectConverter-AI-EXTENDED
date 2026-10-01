package de.mossgrabers.projectconverter.format.aaf;

import org.apache.poi.ssff.SSFF4File;
import org.apache.poi.ssff.ssef.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class AAFParser {

    /**
     * Parses a top-level composition from an .AAF file.
     * Returns a list of AudioClip objects representing the audio tracks/clips found in the AAF.
     */
    public static List<AudioClip> parse(File aafFile) throws Exception {
        if (!aafFile.exists()) throw new Exception("File " + aafFile.getPath() + " not found.");

        SSFF4File file = new SSFF4File(aafFile);
        SSFF4Input input = file.createSSFF4Document();
        SSFF4DataReader reader = input.getReader();

        List<AudioClip> clips = new ArrayList<>();

        // Read all objects in the document
        while (!reader.isEndOfDocument()) {
            SSGObject obj = reader.readNextSSGObject(reader);

            if (obj instanceof SSFF4Composition) {
                SSFF4Composition comp = (SSFF4Composition) obj;
                
                // Check if this is a top-level composition
                if (!isRoot(comp)) {
                    continue;
                }

                AAFComposition compObj = new AAFComposition(comp, reader, input);
                clips.addAll(compObj.getTopLevelClips());
            } else if (obj instanceof SSFF4Clip) {
                // Handle sub-clips / nested compositions
                SSFF4Clip clip = (SSFF4Clip) obj;
                if (!isRoot(clip)) continue;

                AAFClip clipObj = new AAFClip(clip, reader, input);
                clips.add(clipObj);
            } else {
                reader.skipNextSSGObject();
            }
        }

        return clips;
    }

    private static boolean isRoot(SSFF4Composition comp) throws Exception {
        SSFF4Reference parent = comp.getProperty(SSFF4Property.PARENT_COMPOSITION, null);
        if (parent == null) return true; // No parent means root
        String val = parent.getValue();
        return val.equals("root") || val.isEmpty();
    }

    /**
     * Parses a single clip from an AAF file.
     */
    public static AudioClip parseClip(File aafFile, long offset) throws Exception {
        SSFF4File file = new SSFF4File(aafFile);
        SSFF4Input input = file.createSSFF4Document();
        SSFF4DataReader reader = input.getReader();

        // Seek to the specified offset (clip position)
        reader.seekToOffset(offset);

        SSGObject obj = reader.readNextSSGObject(reader);
        if (!(obj instanceof SSFF4Clip)) {
            throw new Exception("Expected Clip object at offset " + offset);
        }

        SSFF4Clip clip = (SSFF4Clip) obj;
        return new AAFClip(clip, reader, input);
    }

    // ---------------------------------------------------------------------------
    // Nested classes for parsing
    // ---------------------------------------------------------------------------

    private static class AAFComposition extends SSFF4Object implements SSFF4Composition {
        public final SSFF4Reference PARENT_COMPOSITION;
        public final List<AAFClip> topLevelClips = new ArrayList<>();

        AAFComposition(SSFF4DataReader reader, SSFF4Input input) throws Exception {
            super(reader); // Position 0 is the clip itself (composition ID)
            
            PARENT_COMPOSITION = getProperty(SSFF4Property.PARENT_COMPOSITION, null);
            AUDIO_SOURCE = getProperty(SSFF4Property.AUDIO_SOURCE, null);

            // Read all children of this composition
            while (!reader.isEndOfDocument()) {
                SSGObject child = reader.readNextSSGObject(reader);

                if (child instanceof SSFF4Clip) {
                    SSFF4Clip clipChild = (SSFF4Clip) child;
                    if (!isRoot(clipChild)) { // Skip sub-compositions, only want top-level clips
                        AAFClip clipObj = new AAFClip(clipChild, reader, input);
                        topLevelClips.add(clipObj);
                    }
                } else if (child instanceof SSFF4Composition) {
                    SSFF4Composition compChild = (SSFF4Composition) child;
                    // Recursively process nested compositions
                    AAFComposition subComp = new AAFComposition(compChild, reader, input);
                    topLevelClips.addAll(subComp.getTopLevelClips());
                } else if (!(child instanceof SSGArray)) {
                    reader.skipNextSSGObject();
                }
            }
        }

        private static boolean isRoot(SSFF4Clip clip) throws Exception {
            SSFF4Reference parent = clip.getProperty(SSFF4Property.PARENT_COMPOSITION, null);
            if (parent == null) return true;
            String val = parent.getValue();
            return val.equals("root") || val.isEmpty();
        }

        private static boolean isRoot(SSFF4Composition comp) throws Exception {
            SSFF4Reference parent = comp.getProperty(SSFF4Property.PARENT_COMPOSITION, null);
            if (parent == null) return true;
            String val = parent.getValue();
            return val.equals("root") || val.isEmpty();
        }

        public List<AAFClip> getTopLevelClips() { return topLevelClips; }
    }

    private static class AAFClip extends SSFF4Object implements SSFF4Composition {
        public final SSFF4Reference PARENT_COMPOSITION;
        public final SSFF4Reference AUDIO_SOURCE;
        public final SSFF4Reference EDIT_RATE;
        public final long startTime;
        public final long endTime;
        public final String name;

        AAFClip(SSFF4DataReader reader, SSFF4Input input) throws Exception {
            super(reader);

            PARENT_COMPOSITION = getProperty(SSFF4Property.PARENT_COMPOSITION, null);
            AUDIO_SOURCE = getProperty(SSFF4Property.AUDIO_SOURCE, null);
            EDIT_RATE = getProperty(SSFF4Property.EDIT_RATE, null);

            // Duration (in AAF units - 1/29.97 seconds each)
            SSFF4Reference durationRef = getProperty(SSFF4Property.DURATION, null);
            if (durationRef != null && !durationRef.getValue().isEmpty()) {
                long dur = Long.parseLong(durationRef.getValue());
                startTime = reader.getOffset() + getOffset(); // clip start position
                endTime = startTime + dur;
            } else {
                // Fallback: read from the source file info if available
                SSFF4Reference srcRef = getProperty(SSFF4Property.SOURCE_FILE, null);
                if (srcRef != null) {
                    File sourceFile = new File(srcRef.getValue());
                    this.startTime = 0;
                    this.endTime = getFileLength(sourceFile); // approximate duration
                } else {
                    startTime = 0;
                    endTime = 1000L * 60; // default: 1 minute in AAF units
                }
            }

            // Name from object ID or properties
            this.name = getProperty(SSFF4Property.NAME, null) != null 
                       ? getProperty(SSFF4Property.NAME).getValue()
                       : "Clip-" + (System.currentTimeMillis() / 1000);
        }

        @Override public SSFF4Reference getParentComposition() { return PARENT_COMPOSITION; }
        @Override public SSFF4Reference getAudioSource() { return AUDIO_SOURCE; }
        @Override public SSFF4Reference getEditRate() { return EDIT_RATE; }

        private long getFileLength(File file) throws Exception {
            if (file.exists()) {
                // Try to read the audio source info from the AAF
                SSFF4File aaf = new SSFF4File(file);
                SSFF4Input input = aaf.createSSFF4Document();
                SSFF4DataReader reader = input.getReader();

                while (!reader.isEndOfDocument()) {
                    SSGObject obj = reader.readNextSSGObject(reader);
                    if (obj instanceof SSFF4FileSource fs) {
                        return fs.getSize(); // duration in AAF units
                    }
                }
            }
            return 1000L * 60; // default: 1 minute
        }

        private SSFF4Property getProperty(SSFF4Property prop, Throwable error) throws Exception {
            try {
                SSFGObject child = getNestedProperty(prop);
                if (child != null && !(child instanceof SSGArray)) {
                    SSFF4Property pprop = ((SSFF4Composition) this).getProperty(prop);
                    if (pprop != null && !pprop.getValue().isEmpty()) return pprop;
                } else if (child == null) {
                    throw error;
                } else if (error == null) return null;
            } catch (Exception e) {
                // Skip unknown property types and try next
            }

            throw error;
        }

        private SSFGObject getNestedProperty(SSFF4Property prop) throws Exception {
            SSFGObject childObj = getProperty(prop);
            
            while (childObj instanceof SSGArray arr && !(childObj instanceof SSFF4Reference)) {
                // Find the first non-empty reference in this array
                for (int i = 0; i < arr.size(); i++) {
                    SSGObject next = childObj.readNextSSGObject(childObj);
                    if (!(next instanceof SSFF4Reference ref)) continue;

                    String val = ref.getValue();
                    if (!val.isEmpty() && !val.equals("null") && !val.equals("\"\"")) {
                        return ref; // found a valid reference
                    }
                }
                // No valid reference found, move to next sibling
                childObj = getProperty(prop); // try again with next array element
            }

            return childObj;
        }

        private SSFGObject getProperty(SSFF4Property prop) throws Exception {
            SSFGObject obj = (SSFF4Composition) this;
            
            if (!(obj instanceof SSFF4Object)) return null;
            SSFF4Property property = ((SSFF4Object) obj).getProperty(prop);

            if (property == null || property.getValue().isEmpty()) return null;
            return property;
        }
    }

    // ---------------------------------------------------------------------------
    // Helper classes for SSFF types
    // ---------------------------------------------------------------------------

    private static class SSGArray extends SSGObject {
        final int size;
        long nextOffset = -1;

        SSGArray(SSFF4DataReader reader) throws Exception {
            super(reader);
            this.size = ((SSFGObject) this).getValue().length; // simplified
        }

        @Override public SSGObject readNextSSGObject(SSFF4DataReader reader) throws Exception {
            if (nextOffset >= 0 && nextOffset < reader.getFileSize()) {
                reader.seekToOffset(nextOffset);
                return reader.readNextSSGObject(reader);
            } else {
                throw new IllegalStateException("End of array at offset " + nextOffset);
            }
        }

        @Override public void skipNextSSGObject(SSFF4DataReader reader) throws Exception {
            if (nextOffset >= 0 && nextOffset < reader.getFileSize()) {
                // Skip this element and move to next
                nextOffset = reader.getOffset() + ((SSFGArray) this).getNextElementSize();
            }
        }

        @Override public long getNextElementSize() throws Exception {
            return reader.readNextSSGObject(reader).getSizeInBytes();
        }
    }

}