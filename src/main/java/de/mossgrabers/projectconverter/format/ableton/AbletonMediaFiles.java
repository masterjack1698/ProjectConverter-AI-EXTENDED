// Original implementations by Jürgen Moßgraber - mossgrabers.de
// Gemma 4 12b - Gemini 3.x flash vibe coded following Jürgen Moßgraber work as a guideline
// (c) 2021-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.projectconverter.format.ableton;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.mossgrabers.projectconverter.core.IMediaFiles;

/**
 * Media file handler for Ableton Live projects.
 *
 * @author Jurgen Mossgraber
 */
public class AbletonMediaFiles implements IMediaFiles
{
    private final Map<String, File> mediaMap = new HashMap<> ();
    private final Map<String, byte[]> memoryMap = new HashMap<> ();

    public AbletonMediaFiles (final File sourceFile)
    {
    }

    @Override
    public InputStream stream (String id) throws IOException
    {
        if (memoryMap.containsKey (id))
            return new ByteArrayInputStream (memoryMap.get (id));

        File file = mediaMap.get (id);
        if (file == null || !file.exists ())
        {
            System.out.println ("[MEDIA ERROR] File non trovato nella mappa per ID: " + id);
            throw new IOException ("Media file not found: " + id);
        }

        return new FileInputStream (file);
    }

    @Override
    public void add (String id, File mediaFile)
    {
        if (mediaFile != null && mediaFile.exists ())
            mediaMap.put (id, mediaFile);
    }

    public void addBytes (String id, byte[] data)
    {
        if (data != null)
            memoryMap.put (id, data);
    }

    @Override
    public List<String> getAll ()
    {
        List<String> keys = new ArrayList<> (mediaMap.keySet ());
        keys.addAll (memoryMap.keySet ());
        return keys;
    }

    @Override
    public void close () throws IOException
    {
        mediaMap.clear ();
        memoryMap.clear ();
    }

    public File resolvePath (String path, File sourceFile) {
        if (path == null || path.isEmpty ())
            return null;

        Path projectDir = Paths.get (sourceFile.getAbsolutePath ()).getParent ();
        if (projectDir == null)
            return null;

        String fileName = Paths.get (path).getFileName ().toString ();

        File samplesDir = projectDir.resolve ("Samples").toFile ();
        if (samplesDir.exists () && samplesDir.isDirectory ()) {
            File found = searchFileRecursively (samplesDir, fileName);
            if (found != null)
                return found;
        }

        File absoluteFile = new File (path);
        if (absoluteFile.exists ())
            return absoluteFile;

        Path resolvedPath = projectDir.resolve (path).normalize ();
        File relativeFile = resolvedPath.toFile ();
        if (relativeFile.exists ())
            return relativeFile;

        return null;
    }

    private File searchFileRecursively (File folder, String targetFileName) {
        File[] files = folder.listFiles ();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory ()) {
                    File found = searchFileRecursively (file, targetFileName);
                    if (found != null)
                        return found;
                } else if (file.getName ().equalsIgnoreCase (targetFileName)) {
                    return file;
                }
            }
        }
        return null;
    }
}