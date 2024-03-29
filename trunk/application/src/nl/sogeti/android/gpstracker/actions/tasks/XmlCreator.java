/*------------------------------------------------------------------------------
 **     Ident: Sogeti Smart Mobile Solutions
 **    Author: rene
 ** Copyright: (c) Apr 24, 2011 Sogeti Nederland B.V. All Rights Reserved.
 **------------------------------------------------------------------------------
 ** Sogeti Nederland B.V.            |  No part of this file may be reproduced  
 ** Distributed Software Engineering |  or transmitted in any form or by any        
 ** Lange Dreef 17                   |  means, electronic or mechanical, for the      
 ** 4131 NJ Vianen                   |  purpose, without the express written    
 ** The Netherlands                  |  permission of the copyright holder.
 *------------------------------------------------------------------------------
 *
 *   This file is part of OpenGPSTracker.
 *
 *   OpenGPSTracker is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   OpenGPSTracker is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with OpenGPSTracker.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package nl.sogeti.android.gpstracker.actions.tasks;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import nl.sogeti.android.gpstracker.actions.utils.ProgressListener;
import nl.sogeti.android.gpstracker.db.GPStracking.Media;
import nl.sogeti.android.gpstracker.db.GPStracking.Tracks;
import nl.sogeti.android.gpstracker.db.GPStracking.Waypoints;
import nl.sogeti.android.gpstracker.util.Constants;

import org.xmlpull.v1.XmlSerializer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.view.Window;

/**
 * Async XML creation task Execute without parameters (Void) Update posted with
 * single Integer And result is a filename in a String
 * 
 * @version $Id: XmlCreator.java 1193 2012-01-01 12:07:41Z rcgroot $
 * @author rene (c) May 29, 2011, Sogeti B.V.
 */
public abstract class XmlCreator extends AsyncTask<Void, Integer, Uri>
{
   private String TAG = "OGT.XmlCreator";
   private String mExportDirectoryPath;
   private boolean mNeedsBundling;

   String mChosenName;
   private ProgressListener mProgressListener;
   protected Context mContext;
   protected Uri mTrackUri;
   String mFileName;
   private String mErrorText;
   private Exception mException;
   private String mTask;
   public ProgressAdmin mProgressAdmin;

   XmlCreator(Context context, Uri trackUri, String chosenFileName, ProgressListener listener)
   {
      mChosenName = chosenFileName;
      mContext = context;
      mTrackUri = trackUri;
      mProgressListener = listener;
      mProgressAdmin = new ProgressAdmin();
      
      String trackName = extractCleanTrackName();
      mFileName = cleanFilename(mChosenName, trackName);
   }

   private String extractCleanTrackName()
   {
      Cursor trackCursor = null;
      ContentResolver resolver = mContext.getContentResolver();
      String trackName = "Untitled";
      try
      {
         trackCursor = resolver.query(mTrackUri, new String[] { Tracks.NAME }, null, null, null);
         if (trackCursor.moveToLast())
         {
            trackName = cleanFilename(trackCursor.getString(0), trackName);
         }
      }
      finally
      {
         if (trackCursor != null)
         {
            trackCursor.close();
         }
      }
      return trackName;
   }

   /**
    * Calculated the total progress sum expected from a export to file This is
    * the sum of the number of waypoints and media entries times 100. The whole
    * number is doubled when compression is needed.
    */
   public void determineProgressGoal()
   {
      if (mProgressListener != null)
      {
         Uri allWaypointsUri = Uri.withAppendedPath(mTrackUri, "waypoints");
         Uri allMediaUri = Uri.withAppendedPath(mTrackUri, "media");
         Cursor cursor = null;
         ContentResolver resolver = mContext.getContentResolver();
         try
         {
            cursor = resolver.query(allWaypointsUri, new String[] { "count(" + Waypoints.TABLE + "." + Waypoints._ID + ")" }, null, null, null);
            if (cursor.moveToLast())
            {
               mProgressAdmin.setWaypointCount(cursor.getInt(0));
            }
            cursor.close();
            cursor = resolver.query(allMediaUri, new String[] { "count(" + Media.TABLE + "." + Media._ID + ")" }, null, null, null);
            if (cursor.moveToLast())
            {
                mProgressAdmin.setMediaCount(cursor.getInt(0));
            }
            cursor.close();
            cursor = resolver.query(allMediaUri, new String[] { "count(" + Tracks._ID + ")" }, Media.URI + " LIKE ? and " + Media.URI + " NOT LIKE ?",
                  new String[] { "file://%", "%txt" }, null);
            if (cursor.moveToLast())
            {
               mProgressAdmin.setCompress( cursor.getInt(0) > 0 ); 
            }
         }
         finally
         {
            if (cursor != null)
            {
               cursor.close();
            }
         }
      }
      else
      {
         Log.w(TAG, "Exporting " + mTrackUri + " without progress!");
      }
   }

   /**
    * Removes all non-word chars (\W) from the text
    * 
    * @param fileName
    * @param defaultName
    * @return a string larger then 0 with either word chars remaining from the
    *         input or the default provided
    */
   public static String cleanFilename(String fileName, String defaultName)
   {
      if (fileName == null || "".equals(fileName))
      {
         fileName = defaultName;
      }
      else
      {
         fileName = fileName.replaceAll("\\W", "");
         fileName = (fileName.length() > 0) ? fileName : defaultName;
      }
      return fileName;
   }

   /**
    * Includes media into the export directory and returns the relative path of
    * the media
    * 
    * @param inputFilePath
    * @return file path relative to the export dir
    * @throws IOException
    */
   protected String includeMediaFile(String inputFilePath) throws IOException
   {
      mNeedsBundling = true;
      File source = new File(inputFilePath);
      File target = new File(mExportDirectoryPath + "/" + source.getName());

      //      Log.d( TAG, String.format( "Copy %s to %s", source, target ) ); 
      if (source.exists())
      {
         FileChannel inChannel = new FileInputStream(source).getChannel();
         FileChannel outChannel = new FileOutputStream(target).getChannel();
         try
         {
            inChannel.transferTo(0, inChannel.size(), outChannel);
         }
         finally
         {
            if (inChannel != null) inChannel.close();
            if (outChannel != null) outChannel.close();
         }
      }
      else
      {
         Log.w( TAG, "Failed to add file to new XML export. Missing: "+inputFilePath );
      }
      mProgressAdmin.addMediaProgress();

      return target.getName();
   }

   /**
    * Just to start failing early
    * 
    * @throws IOException
    */
   protected void verifySdCardAvailibility() throws IOException
   {
      String state = Environment.getExternalStorageState();
      if (!Environment.MEDIA_MOUNTED.equals(state))
      {
         throw new IOException("The ExternalStorage is not mounted, unable to export files for sharing.");
      }
   }

   /**
    * Create a zip of the export directory based on the given filename
    * 
    * @param fileName The directory to be replaced by a zipped file of the same
    *           name
    * @param extension
    * @return full path of the build zip file
    * @throws IOException
    */
   protected String bundlingMediaAndXml(String fileName, String extension) throws IOException
   {
      String zipFilePath;
      if (fileName.endsWith(".zip") || fileName.endsWith(extension))
      {
         zipFilePath = Constants.getSdCardDirectory(mContext) + fileName;
      }
      else
      {
         zipFilePath = Constants.getSdCardDirectory(mContext) + fileName + extension;
      }
      String[] filenames = new File(mExportDirectoryPath).list();
      byte[] buf = new byte[1024];
      ZipOutputStream zos = null;
      try
      {
         zos = new ZipOutputStream(new FileOutputStream(zipFilePath));
         for (int i = 0; i < filenames.length; i++)
         {
            String entryFilePath = mExportDirectoryPath + "/" + filenames[i];
            FileInputStream in = new FileInputStream(entryFilePath);
            zos.putNextEntry(new ZipEntry(filenames[i]));
            int len;
            while ((len = in.read(buf)) >= 0)
            {
               zos.write(buf, 0, len);
            }
            zos.closeEntry();
            in.close();
            mProgressAdmin.addCompressProgress();
         }
      }
      finally
      {
         if (zos != null)
         {
            zos.close();
         }
      }

      deleteRecursive(new File(mExportDirectoryPath));

      return zipFilePath;
   }

   public static boolean deleteRecursive(File file)
   {
      if (file.isDirectory())
      {
         String[] children = file.list();
         for (int i = 0; i < children.length; i++)
         {
            boolean success = deleteRecursive(new File(file, children[i]));
            if (!success)
            {
               return false;
            }
         }
      }
      return file.delete();
   }

   public void setExportDirectoryPath(String exportDirectoryPath)
   {
      this.mExportDirectoryPath = exportDirectoryPath;
   }

   public String getExportDirectoryPath()
   {
      return mExportDirectoryPath;
   }

   public void quickTag(XmlSerializer serializer, String ns, String tag, String content) throws IllegalArgumentException, IllegalStateException, IOException
   {
      if( tag == null)
      {
         tag = "";
      }
      if( content == null)
      {
         content = "";
      }
      serializer.text("\n");
      serializer.startTag(ns, tag);
      serializer.text(content);
      serializer.endTag(ns, tag);
   }

   public boolean needsBundling()
   {
      return mNeedsBundling;
   }

   public static String convertStreamToString(InputStream is) throws IOException
   {
      String result = "";
      /*
       * To convert the InputStream to String we use the Reader.read(char[]
       * buffer) method. We iterate until the Reader return -1 which means
       * there's no more data to read. We use the StringWriter class to produce
       * the string.
       */
      if (is != null)
      {
         Writer writer = new StringWriter();

         char[] buffer = new char[8192];
         try
         {
            Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            int n;
            while ((n = reader.read(buffer)) != -1)
            {
               writer.write(buffer, 0, n);
            }
         }
         finally
         {
            is.close();
         }
         result = writer.toString(); 
      }
      return result;
   }
   

   public static InputStream convertStreamToLoggedStream(String tag, InputStream is) throws IOException
   {
      String result = "";
      /*
       * To convert the InputStream to String we use the Reader.read(char[]
       * buffer) method. We iterate until the Reader return -1 which means
       * there's no more data to read. We use the StringWriter class to produce
       * the string.
       */
      if (is != null)
      {
         Writer writer = new StringWriter();

         char[] buffer = new char[8192];
         try
         {
            Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            int n;
            while ((n = reader.read(buffer)) != -1)
            {
               writer.write(buffer, 0, n);
            }
         }
         finally
         {
            is.close();
         }
         result = writer.toString(); 
      }
      InputStream in = new ByteArrayInputStream(result.getBytes("UTF-8"));  
      return in;
   }

   protected abstract String getContentType();

   protected void handleError(String task, Exception e, String text)
   {
      Log.e(TAG, "Unable to save ", e);
      mTask = task;
      mException = e;
      mErrorText = text;
      cancel(false);
      throw new CancellationException(text);
   }
   
   @Override
   protected void onPreExecute()
   {
      if(mProgressListener!= null)
      {
         mProgressListener.started();
      }
   }

   @Override
   protected void onProgressUpdate(Integer... progress)
   {
      if(mProgressListener!= null)
      {
         mProgressListener.setProgress(mProgressAdmin.getProgress());
      }
   }

   @Override
   protected void onPostExecute(Uri resultFilename)
   {
      if(mProgressListener!= null)
      {
         mProgressListener.finished(resultFilename);
      }
   }

   @Override
   protected void onCancelled()
   {
      if(mProgressListener!= null)
      {
         mProgressListener.finished(null);
         mProgressListener.showError(mTask, mErrorText, mException);
      }
   }
   
   public class ProgressAdmin
   {
      long lastUpdate;
      private boolean compressCount;
      private boolean compressProgress;
      private boolean uploadCount;
      private boolean uploadProgress;
      private int mediaCount;
      private int mediaProgress;
      private int waypointCount;
      private int waypointProgress;
      private long photoUploadCount ;
      private long photoUploadProgress ;
      
      public void addMediaProgress()
      {
         mediaProgress ++;
      }
      public void addCompressProgress()
      {
         compressProgress = true;
      }
      public void addUploadProgress()
      {
         uploadProgress = true;
      }
      
      public void addPhotoUploadProgress(long length)
      {
         photoUploadProgress += length;
      }
      
      /**
       * Get the progress on scale 0 ... Window.PROGRESS_END
       *
       * @return Returns the progress as a int.
       */
      public int getProgress()
      {
         int blocks = 0;
         if( waypointCount > 0     ){ blocks++; }
         if( mediaCount > 0        ){ blocks++; }
         if( compressCount         ){ blocks++; }
         if( uploadCount           ){ blocks++; }
         if( photoUploadCount > 0 ){ blocks++; }
         int progress;
         if( blocks > 0 )
         {
            int blockSize = Window.PROGRESS_END / blocks;
            progress  = waypointCount > 0    ? blockSize * waypointProgress / waypointCount : 0;
            progress += mediaCount > 0       ? blockSize * mediaProgress / mediaCount : 0;
            progress += compressProgress     ? blockSize : 0;
            progress += uploadProgress       ? blockSize : 0;
            progress += photoUploadCount > 0 ? blockSize * photoUploadProgress / photoUploadCount : 0;
         }
         else
         {
            progress = 0;
         }
         //Log.d( TAG, "Progress updated to "+progress);
         return progress;
      }
      public void setWaypointCount(int waypoint)
      {
         waypointCount = waypoint;
         considerPublishProgress();
      }
      public void setMediaCount(int media)
      {
         mediaCount = media;
         considerPublishProgress();
      }
      public void setCompress( boolean compress)
      {
         compressCount = compress;
         considerPublishProgress();
      }
      public void setUpload( boolean upload)
      {
         uploadCount = upload;
         considerPublishProgress();
      }
      public void setPhotoUpload(long length)
      {
         photoUploadCount += length;
         considerPublishProgress();
      }
      public void addWaypointProgress(int i)
      {
         waypointProgress += i;
         considerPublishProgress();
      } 
      public void considerPublishProgress()
      {
         long now = new Date().getTime();
         if( now - lastUpdate > 1000 )
         {
            lastUpdate = now;
            publishProgress();
         }         
      }
   }
}
