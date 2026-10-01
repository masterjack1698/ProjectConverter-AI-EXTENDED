package de.mossgrabers.projectconverter.format.aaf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.bitwig.dawproject.Project;
import com.bitwig.dawproject.timeline.*;
import com.bitwig.dawproject.timeline.Clips;
import com.bitwig.dawproject.timeline.Lanes;
import com.bitwig.dawproject.timeline.Marker;
import com.bitwig.dawproject.timeline.Markers;

import de.mossgrabers.projectconverter.core.AbstractCoreTask;
import de.mossgrabers.projectconverter.INotifier;
import de.mossgrabers.tools.ui.panel.BoxPanel;
import javafx.geometry.Orientation;

/**
 * Creates a Reaper project (.rpp) from AAF top-level compositions.
 * 
 * Maps each audio clip in the AAF to a File Source track/clip in Reaper.
 */
public class ReaperCreatorAAF extends AbstractCoreTask implements IDestinationFormat {

    public ReaperCreatorAAF(INotifier notifier) {
        super("AAF → Reaper", notifier);
    }

    @Override
    public String getExtension() { return ".rpp"; }

    /**
     * Creates a Reaper project from AAF clips.
     */
    public void createProject(List<AAFClip> clips, File outputDir) throws Exception {
        // Use Bitwig DAW Project API to build the Reaper .rpp file
        Project project = new Project.Builder().build();

        String projectName = "Imported from AAF - " + LocalDateTime.now()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        Arrangement arrangement = project.createArrangement("Main", true, false);
        Timeline timeline = arrangement.getTimeline(1); // 1 essence track (audio)

        // Create audio tracks for each AAF clip
        int trackIndex = 0;
        Clips clipsList = timeline.getClips();
        Markers markers = timeline.getMarkers();

        for (AAFClip aafClip : clips) {
            Track track = arrangement.createTrack("Audio " + (++trackIndex), ContentType.Audio, true);
            
            // Create an audio source clip from the AAF file reference
            String fileSourcePath = extractFileSource(aafClip);
            if (fileSourcePath != null && new File(fileSourcePath).exists()) {
                AudioSourceClip clip = timeline.createClip(track.getTrackIndex(), 
                    0, aafClip.getEndTime() - aafClip.getStartTime());

                // Add file source to the clip (this requires Reaper's internal API)
            }
        }

        project.save(outputDir.getAbsolutePath() + "/imported-from-aaf.rpp");
    }

    private String extractFileSource(AAFClip clip) {
        if (clip.getAudioSource() != null && !clip.getAudioSource().getValue().isEmpty()) {
            // The audio source reference points to a file in the AAF archive
            return "file://" + clip.getAudioSource().getValue();
        }
        return null;
    }

}
