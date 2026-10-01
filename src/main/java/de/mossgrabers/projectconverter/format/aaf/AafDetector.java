// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.aaf;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

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


/**
 * Detector/reader for AAF (Advanced Authoring Format) files.
 *
 * Reads an AAF project and converts it into a dawproject structure, which can then be
 * written to any destination format (e.g. Reaper .rpp) by the existing destination
 * implementations.
 *
 * Current scope: audio tracks and their clips. Video essence is ignored.
 *
 * @author Jürgen Moßgraber
 */
public class AafDetector extends AbstractCoreTask implements ISourceFormat
{
    private static final ExtensionFilter EXTENSION_FILTER = new ExtensionFilter ("AAF Project", "*.aaf");


    /**
     * Constructor.
     *
     * @param notifier The notifier for error messages
     */
    public AafDetector (final INotifier notifier)
    {
        super ("AAF", notifier);
    }


    /** {@inheritDoc} */
    @Override
    public Node getEditPane ()
    {
        final VBox panel = new VBox (10);
        panel.getChildren ().add (new Label ("Reads audio tracks and clips from AAF files.\nVideo and effects are currently not supported."));
        return panel;
    }


    /** {@inheritDoc} */
    @Override
    public void saveSettings (final BasicConfig config)
    {
        // No settings yet
    }


    /** {@inheritDoc} */
    @Override
    public void loadSettings (final BasicConfig config)
    {
        // No settings yet
    }


    /** {@inheritDoc} */
    @Override
    public void shutdown ()
    {
        // Nothing to clean up
    }


    /** {@inheritDoc} */
    @Override
    public void cancel ()
    {
        // Nothing to cancel
    }


    /** {@inheritDoc} */
    @Override
    public ExtensionFilter getExtensionFilter ()
    {
        return EXTENSION_FILTER;
    }


    /** {@inheritDoc} */
    @Override
    public DawProjectContainer read (final File sourceFile) throws IOException, ParseException
    {
        Referenceable.setAutoID (true);

        final File extractionFolder = Files.createTempDirectory ("aaf-extract").toFile ();

        final AAFParser.AafProject aafProject = AAFParser.parse (sourceFile, extractionFolder);

        try (final DawProjectContainer dawProject = new DawProjectContainer (getNameWithoutExtension (sourceFile), new AafMediaFiles ()))
        {
            final Project project = dawProject.getProject ();
            project.application.name = "AAF (converted with ProjectConverter)";

            project.arrangement = new Arrangement ();
            project.arrangement.lanes = new Lanes ();
            TimeUtils.setTimeUnit (project.arrangement.lanes, false);

            final List<Track> tracks = new ArrayList<> ();

            int trackCounter = 0;
            try (final IMediaFiles mediaFiles = dawProject.getMediaFiles ())
            {
                for (final AAFParser.AudioClip audioClip: aafProject.audioClips)
                {
                    trackCounter++;

                    final Track track = new Track ();
                    track.name = "Audio " + trackCounter + " (" + audioClip.streamName + ")";
                    track.contentType = new ContentType []
                    {
                        ContentType.AUDIO
                    };
                    track.channel = new Channel ();
                    tracks.add (track);

                    // Create the lanes of the track
                    final Lanes trackLanes = new Lanes ();
                    trackLanes.track = track;
                    project.arrangement.lanes.lanes.add (trackLanes);

                    // One clip lane with one clip for the extracted audio
                    final Clips clips = new Clips ();
                    trackLanes.lanes.add (clips);
                    TimeUtils.setTimeUnit (clips, false);

                    final Audio audio = new Audio ();
                    audio.file = new FileReference ();
                    audio.file.path = "samples/" + audioClip.audioFile.getName ();
                    audio.algorithm = "raw";
                    audio.duration = Double.valueOf (audioClip.durationSeconds);

                    final Clip clip = new Clip ();
                    clip.name = audioClip.audioFile.getName ();
                    // TODO: The real start positions are stored in the AAF object model
                    // (Sequence/SourceClip), which is not decoded yet. All clips are
                    // therefore placed at position 0 for now.
                    clip.time = Double.valueOf (0);
                    clip.duration = Double.valueOf (audioClip.durationSeconds);
                    clip.playStart = Double.valueOf (0);
                    clip.content = audio;
                    clips.clips.add (clip);

                    mediaFiles.add ("samples/" + audioClip.audioFile.getName (), audioClip.audioFile);

                    this.notifier.log ("IDS_NOTIFY_AAF_EXTRACTED_AUDIO", audioClip.streamName);
                }
            }

            if (aafProject.audioClips.isEmpty ())
                this.notifier.logError ("IDS_NOTIFY_AAF_NO_AUDIO");

            project.structure.addAll (tracks);

            return dawProject;
        }
    }


    /**
     * Get the name of a file without its extension.
     *
     * @param file The file
     * @return The name without extension
     */
    private static String getNameWithoutExtension (final File file)
    {
        final String name = file.getName ();
        final int pos = name.lastIndexOf ('.');
        return pos < 0 ? name : name.substring (0, pos);
    }
}
