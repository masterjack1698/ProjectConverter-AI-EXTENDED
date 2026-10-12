package de.mossgrabers.projectconverter.format.ptx;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import de.mossgrabers.projectconverter.core.IMediaFiles;


/**
 * Media files for Pro Tools .ptx format.
 *
 * @author Jürgen Moßgraber
 */
public class PtxMediaFiles implements IMediaFiles
{
    private final HashMap<String, File> mediaFiles = new HashMap<>();


    public PtxMediaFiles () {}


    @Override
    public InputStream stream (String id) throws IOException
    {
        File file = mediaFiles.get(id);
        if (file == null) {
            throw new IOException ("Media file not found: " + id);
        }
        return new FileInputStream(file);
    }


    @Override
    public void add (String id, File mediaFile)
    {
        mediaFiles.put(id, mediaFile);
    }


    @Override
    public List<String> getAll ()
    {
        return new ArrayList<>(mediaFiles.keySet());
    }


    @Override
    public void close () throws IOException
    {
        // Nothing to close
    }
}