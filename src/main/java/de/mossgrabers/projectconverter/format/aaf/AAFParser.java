// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2021-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.aaf;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;


/**
 * Reads an AAF file (Advanced Authoring Format).
 *
 * An AAF file is an OLE2 Compound File (Microsoft Structured Storage). Apache POI provides
 * access to the storage structure (streams and storages) but not to the AAF object model
 * (Mobs, SourceClips, Sequences, PCMDescriptors, ...). Decoding that object model requires
 * either the official AAF SDK or a substantial hand-written binary parser (TODO).
 *
 * As a first pragmatic step this parser extracts all embedded audio essence (WAV/PCM data
 * stored in the OLE2 streams) which covers the primary goal: audio tracks and their clips.
 *
 * @author Jürgen Moßgraber
 */
public class AAFParser
{
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
     * The result of parsing an AAF file.
     */
    public static class AafProject
    {
        /** The extracted audio clips. */
        public final List<AudioClip> audioClips = new ArrayList<> ();
        /** The names of all streams found in the OLE2 storage (for diagnostics). */
        public final List<String>    streamNames = new ArrayList<> ();
    }


    /**
     * Parses an AAF file and extracts all embedded audio essence.
     *
     * @param aafFile      The AAF file to read
     * @param outputFolder The folder where to write the extracted audio files
     * @return The parsed project
     * @throws IOException Could not read the file
     */
    public static AafProject parse (final File aafFile, final File outputFolder) throws IOException
    {
        final AafProject project = new AafProject ();

        try (POIFSFileSystem fileSystem = new POIFSFileSystem (aafFile, true))
        {
            scanStorage (fileSystem.getRoot (), "", project, outputFolder);
        }

        return project;
    }


    /**
     * Recursively scans an OLE2 storage for embedded audio (RIFF/WAVE) streams.
     *
     * @param directory     The storage to scan
     * @param path          The current path (for diagnostics)
     * @param project       Where to collect the results
     * @param outputFolder  Where to write the extracted audio files
     * @throws IOException Could not read a stream
     */
    private static void scanStorage (final DirectoryEntry directory, final String path, final AafProject project, final File outputFolder) throws IOException
    {
        for (final Entry entry: directory)
        {
            final String entryPath = path.isEmpty () ? entry.getName () : path + "/" + entry.getName ();

            if (entry instanceof final DirectoryEntry subDirectory)
            {
                scanStorage (subDirectory, entryPath, project, outputFolder);
                continue;
            }

            if (entry instanceof final DocumentEntry document)
            {
                project.streamNames.add (entryPath);

                // TODO: Full AAF object model decoding (dictionary, Mobs, SourceClips,
                // PCMDescriptors, timelines) is not implemented yet. For now extract
                // all streams which contain RIFF/WAVE data (embedded audio essence).
                final File extractedFile = extractIfWaveFile (document, outputFolder);
                if (extractedFile != null)
                    project.audioClips.add (new AudioClip (extractedFile, entryPath, getWaveDuration (extractedFile)));
            }
        }
    }


    /**
     * Checks if the stream contains RIFF/WAVE data and extracts it to a temporary file.
     *
     * @param document     The stream to check
     * @param outputFolder The folder where to write the extracted file
     * @return The extracted file or null if the stream is no WAVE file
     * @throws IOException Could not read the stream
     */
    private static File extractIfWaveFile (final DocumentEntry document, final File outputFolder) throws IOException
    {
        try (DocumentInputStream input = new DocumentInputStream (document))
        {
            // RIFF header: 'R','I','F','F' followed by size and 'WAVE'
            final byte[] header = new byte [12];
            if (input.available () < 12 || input.read (header) != 12)
                return null;

            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F')
                return null;
            if (header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E')
                return null;

            input.reset ();

            final String fileName = sanitizeFileName (document.getName ()) + ".wav";
            final File outputFile = new File (outputFolder, fileName);

            try (OutputStream output = Files.newOutputStream (outputFile.toPath ()))
            {
                final byte[] buffer = new byte [8192];
                int read;
                while ((read = input.read (buffer)) != -1)
                    output.write (buffer, 0, read);
            }

            return outputFile;
        }
    }


    /**
     * Creates a safe file name from a stream name.
     *
     * @param name The stream name
     * @return The sanitized file name
     */
    private static String sanitizeFileName (final String name)
    {
        return name.replaceAll ("[\\\\/:*?\"<>|]", "_");
    }


    /**
     * Reads the duration of a WAVE file in seconds.
     *
     * @param waveFile The WAVE file
     * @return The duration in seconds or 0 if it could not be read
     */
    private static double getWaveDuration (final File waveFile)
    {
        // Simple RIFF/WAVE parsing: find the 'fmt ' chunk for the sample rate and
        // the 'data' chunk for the number of data bytes.
        try (InputStream input = Files.newInputStream (waveFile.toPath ()))
        {
            final byte[] riffHeader = new byte [12];
            if (input.read (riffHeader) != 12)
                return 0;

            long sampleRate = 0;
            long blockAlign = 0;
            long dataLength = 0;

            while (true)
            {
                final byte[] chunkHeader = new byte [8];
                final int read = input.read (chunkHeader);
                if (read != 8)
                    break;

                final String chunkId = new String (chunkHeader, 0, 4, "US-ASCII");
                final long chunkSize = (chunkHeader[4] & 0xFF) | (chunkHeader[5] & 0xFF) << 8 | (chunkHeader[6] & 0xFF) << 16 | (chunkHeader[7] & 0xFF) << 24;

                if ("fmt ".equals (chunkId))
                {
                    final byte[] format = new byte [16];
                    if (input.read (format) != 16)
                        break;
                    sampleRate = (format[4] & 0xFF) | (format[5] & 0xFF) << 8 | (format[6] & 0xFF) << 16 | (format[7] & 0xFF) << 24;
                    blockAlign = (format[12] & 0xFF) | (format[13] & 0xFF) << 8;
                    if (chunkSize > 16)
                        input.skip (chunkSize - 16);
                }
                else if ("data".equals (chunkId))
                {
                    dataLength = chunkSize;
                    break;
                }
                else
                {
                    // Skip unknown chunks (chunks are word aligned)
                    long toSkip = chunkSize + (chunkSize & 1);
                    while (toSkip > 0)
                        toSkip -= input.skip (toSkip);
                }
            }

            if (sampleRate > 0 && blockAlign > 0 && dataLength > 0)
                return dataLength / (double) (sampleRate * blockAlign);
        }
        catch (final IOException ex)
        {
            // Fall through
        }
        return 0;
    }
}
