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
 * The AAF object model (Mobs, TimelineMobSlots, Sequences, SourceClips) is decoded by
 * the AAFParser. From it the timeline positioning of every clip is reconstructed:
 *
 * - clip.time       = accumulated sequence position / edit rate (seconds)
 * - clip.duration   = clip length / edit rate (seconds)
 * - clip.playStart  = trim offset into the source media (seconds)
 *
 * Current scope: audio tracks and their clips. Video essence is ignored.
 *
 * @author J&uuml;rgen Mo&szlig;graber
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

            try (final IMediaFiles mediaFiles = dawProject.getMediaFiles ())
            {
                if (aafProject.tracks.isEmpty ())
                {
                    this.convertFallbackTracks (aafProject, tracks, project, mediaFiles);
                }
                else
                {
                    for (final AAFParser.Track aafTrack: aafProject.tracks)
                    {
                        final Track track = new Track ();
                        track.name = aafTrack.name;
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

                        // One clip lane with one clip for each SourceClip of the sequence
                        final Clips clips = new Clips ();
                        trackLanes.lanes.add (clips);
                        TimeUtils.setTimeUnit (clips, false);

                        for (final AAFParser.ClipEntry clipEntry: aafTrack.clips)
                        {
                            if (clipEntry.audio == null)
                            {
                                // The referenced media could not be matched to an extracted
                                // stream: fall back to the extracted files in order.
                                clipEntry.audio = this.takeUnusedAudio (aafProject);
                            }

                            if (clipEntry.audio == null || clipEntry.audio.audioFile == null)
                                continue;

                            final String mediaID = "samples/" + clipEntry.audio.audioFile.getName ();
                            mediaFiles.add (mediaID, clipEntry.audio.audioFile);

                            final Audio audio = new Audio ();
                            audio.file = new FileReference ();
                            audio.file.path = mediaID;
                            audio.algorithm = "raw";
                            audio.duration = Double.valueOf (clipEntry.audio.durationSeconds);

                            final Clip clip = new Clip ();
                            clip.name = clipEntry.audio.audioFile.getName ();
                            clip.time = Double.valueOf (clipEntry.startSeconds);
                            clip.duration = Double.valueOf (clipEntry.durationSeconds);
                            clip.playStart = Double.valueOf (clipEntry.sourceOffsetSeconds);
                            clip.content = audio;
                            clips.clips.add (clip);

                            this.notifier.log ("IDS_NOTIFY_AAF_CLIP_POSITION", clip.name, formatSeconds (clipEntry.startSeconds), formatSeconds (clipEntry.durationSeconds));
                        }
                    }
                }
            }

            if (aafProject.tracks.isEmpty () && aafProject.audioClips.isEmpty ())
                this.notifier.logError ("IDS_NOTIFY_AAF_NO_AUDIO");
            else if (!aafProject.tracks.isEmpty ())
                this.notifier.log ("IDS_NOTIFY_AAF_TIMELINE_DETECTED", Integer.toString (aafProject.tracks.size ()));

            project.structure.addAll (tracks);

            return dawProject;
        }
    }


    /**
     * Fallback conversion if no timeline structure could be reconstructed from the AAF
     * object model: each extracted audio file gets its own track with a clip at position 0.
     *
     * @param aafProject  The parsed AAF project
     * @param tracks      Where to add the tracks
     * @param project     The dawproject project
     * @param mediaFiles  The media files to register the audio with
     * @throws IOException Could not register a media file
     */
    private void convertFallbackTracks (final AAFParser.AafProject aafProject, final List<Track> tracks, final Project project, final IMediaFiles mediaFiles) throws IOException
    {
        int trackCounter = 0;
        for (final AAFParser.AudioClip audioClip: aafProject.audioClips)
        {
            // Skip references to external files which could not be resolved
            if (audioClip.audioFile == null)
                continue;

            trackCounter++;

            final Track track = new Track ();
            track.name = "Audio " + trackCounter + " (" + audioClip.streamName + ")";
            track.contentType = new ContentType []
            {
                ContentType.AUDIO
            };
            track.channel = new Channel ();
            tracks.add (track);

            final Lanes trackLanes = new Lanes ();
            trackLanes.track = track;
            project.arrangement.lanes.lanes.add (trackLanes);

            final Clips clips = new Clips ();
            trackLanes.lanes.add (clips);
            TimeUtils.setTimeUnit (clips, false);

            final String mediaID = "samples/" + audioClip.audioFile.getName ();
            mediaFiles.add (mediaID, audioClip.audioFile);

            final Audio audio = new Audio ();
            audio.file = new FileReference ();
            audio.file.path = mediaID;
            audio.algorithm = "raw";
            audio.duration = Double.valueOf (audioClip.durationSeconds);

            final Clip clip = new Clip ();
            clip.name = audioClip.audioFile.getName ();
            clip.time = Double.valueOf (0);
            clip.duration = Double.valueOf (audioClip.durationSeconds);
            clip.playStart = Double.valueOf (0);
            clip.content = audio;
            clips.clips.add (clip);

            this.notifier.log ("IDS_NOTIFY_AAF_EXTRACTED_AUDIO", audioClip.streamName);
        }
    }


    /**
     * Takes the next audio clip from the projects fallback list which has not been used
     * for a positioned clip so far.
     *
     * @param aafProject The parsed AAF project
     * @return The next unused audio clip or null if there is none
     */
    private AAFParser.AudioClip takeUnusedAudio (final AAFParser.AafProject aafProject)
    {
        for (final AAFParser.AudioClip clip: aafProject.audioClips)
            if (!clip.used)
            {
                clip.used = true;
                return clip;
            }
        return null;
    }


    /**
     * Formats seconds into a human readable value.
     *
     * @param seconds The seconds
     * @return The formatted value
     */
    private static String formatSeconds (final double seconds)
    {
        return String.format (java.util.Locale.US, "%.3f s", Double.valueOf (seconds));
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
