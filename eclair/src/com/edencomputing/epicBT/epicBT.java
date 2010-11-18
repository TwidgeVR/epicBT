/* --------------------------------------------------------------------------

	This work is free software; you can redistribute it and/or 
	modify it under the terms of the GNU General Public License 
	as published by the Free Software Foundation; either version 2 
	of the License, or any later version.

	This work is distributed in the hope that it will be useful, 
	but without any warranty; without even the implied warranty 
	of merchantability or fitness for a particular purpose. 
	See version 2 and version 3 of the GNU General Public License 
	for more details. You should have received a copy of the 
	GNU General Public License along with this program; if not, write to the 

	Free Software Foundation, Inc., 
	51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA

-------------------------------------------------------------------------- */


package com.edencomputing.epicBT;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.content.res.*;
import android.view.*;
import android.widget.*;
import android.util.Log;

import java.io.*;

public class epicBT extends Activity
{
	private static final String LOGTAG = "epicBT_main";
	/*
		This is a hotfix for bluetooth audio routing when another device is occupying the
		headset (3.5mm) jack.  The intention of this app is to present a toggleable
		interface to turn bluetooth absolute routing either on or off.

		It seems the phone defaults to Headset_incall whenever something is plugged in, regardless
		of what other devices (BT) are connected.  These custom asound.confs simply change the 
		destination for the Headset_incall PCM
	*/
	private static final String DATA_PATH = "/data/data/com.edencomputing.epicBT";
	private static final String BACKUP_PATH = DATA_PATH + "/conf";

	private static final String ASOUND_DEFAULT = BACKUP_PATH + "/asound.conf.default";
	private static final String ASOUND_BT = BACKUP_PATH + "/asound.conf.bt";
	private static final String ASOUND_SPKRPHONE = BACKUP_PATH + "/asound.conf.spkrphone";
	private static final String SYS_ASOUND_CONF = "/system/etc/asound.conf";

	private static final String PREF_NODE = "epicBT_prefs";
	private static final String PREF_SELECTEDROUTER = "selected_router";

	private boolean remountRO = false;

	private RadioGroup select_group;
	private Button btn_apply;
	private Button btn_defaults;

	public void makeMeToast( String str )
	{
		makeMeToast( str, Toast.LENGTH_SHORT );
	}
	public void makeMeToast( String str, int len )
	{
		Toast.makeText( getApplicationContext(), str, len ).show();
	}

	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
		setTitle("epicBT - Call Audio for Epic4G (Eclair)");

		// Move our files out to /data/ if it's our first run - easier access
		File test = new File(ASOUND_DEFAULT);
		if ( !test.exists() )
		{
			Log.v(LOGTAG, "Creating data folders in "+DATA_PATH);
			createDataFolders();
			Log.v(LOGTAG, "Moving our data to "+DATA_PATH);
			moveDataFiles();
		}

		select_group = (RadioGroup)findViewById(R.id.select_group);
		btn_apply = (Button)findViewById(R.id.btn_apply);

		SharedPreferences prefs = getSharedPreferences( PREF_NODE, Context.MODE_PRIVATE );
		int selected_router = prefs.getInt( PREF_SELECTEDROUTER, R.id.rbtn_default );
		((RadioButton)findViewById( selected_router )).toggle();

		Button quit_button = (Button)findViewById(R.id.btn_disabled);

		// Test root access, fail if none
		if ( !hasRoot() )
		{
			makeMeToast("Root access unavailable");
			select_group.setVisibility(View.GONE);
			btn_apply.setVisibility(View.GONE);
			((TextView)findViewById(R.id.root_required)).setVisibility(View.VISIBLE);
			quit_button.setVisibility(View.VISIBLE);
			
			quit_button.setOnClickListener( new View.OnClickListener() 
			{
				public void onClick( View v ) {  finish(); };
			});
		}
			

		// Setup listeners for the buttons
		btn_apply.setOnClickListener( new View.OnClickListener()
		{
			public void onClick( View v )
			{
				boolean processed_ok = true;
				try {
					// Find out which radio option is selected
					switch ( select_group.getCheckedRadioButtonId() )
					{
						case R.id.rbtn_default:
							// Set to headet
							swapAsound( ASOUND_DEFAULT );
						break;
	
						case R.id.rbtn_bluetooth:
							// Set to bluetooth
							swapAsound( ASOUND_BT );
						break;
	
						case R.id.rbtn_spkrphone:
							// Set to speaker phone
							swapAsound( ASOUND_SPKRPHONE );
						break;
					}
					makeMeToast("asound.conf updated successfully");
					Log.v(LOGTAG, "asound.conf updated");
				} catch ( Exception e ) {
					Log.v(LOGTAG, "Some kind of problem writing the new asound.conf: "+ e.toString());
					makeMeToast("Could not overwrite sound config");
					processed_ok = false;
				} 

				try {
					// It's currently necessary to kill the mediaserver
					// to restart alsa
					if ( processed_ok ) 
					{ 
						restartMediaServer(); 
						makeMeToast("mediaserver restarted");
						Log.v(LOGTAG, "mediaserver restarted");
					}
				} catch ( Exception e ) {
					Log.v(LOGTAG, "Couldn't restart mediaserver!");
					processed_ok = false;

				}

				if ( processed_ok )  
				{  // Save the preference for next time
		            SharedPreferences.Editor prefedit = 
							getSharedPreferences(PREF_NODE, Context.MODE_PRIVATE).edit();
					prefedit.putInt( PREF_SELECTEDROUTER, select_group.getCheckedRadioButtonId() );
					prefedit.commit();
				}
			}
		});

	}

	
	public boolean hasRoot()
	{
		boolean hasRoot = true;
		// Test root access, fail if not available or denied.
		Process process = null;
		DataOutputStream ostream = null;
		try {
			process = Runtime.getRuntime().exec("su");
			ostream = new DataOutputStream( process.getOutputStream() );
			ostream.writeBytes("exit\n");
			ostream.flush();
			process.waitFor();

			if ( process.exitValue() != 0 )
			{
				hasRoot = false;
			}
			if ( ostream != null ) { ostream.close(); };
			if ( process != null ) { process.destroy(); };
		} catch ( Exception e ) {
			hasRoot = false;
		}	
		return hasRoot;
	}

	private boolean canWriteEtc()
	{
		Process process = null;
		boolean canWrite = false;
		try {
			process = Runtime.getRuntime().exec("su");
			DataOutputStream ostream = new DataOutputStream( process.getOutputStream() );
			ostream.writeBytes("echo 1 > /etc/edenbt_testfile\n");
			ostream.flush();
			File test = new File("/etc/edenbt_testfile");
			if ( test.exists() ) 
			{ 
				canWrite = true; 
				ostream.writeBytes("rm -f /etc/edenbt_testfile\n");
				ostream.flush();
			} 

			ostream.writeBytes("exit\n");
			ostream.flush();

			process.waitFor();

			ostream.close();
			process.destroy();
		} catch ( Exception e ) {
			Log.v(LOGTAG, "write test failed, cannot access /etc/" );
		}
		return canWrite;
	}

	public void createDataFolders()
	{
		File file = null;
		file = new File( BACKUP_PATH ); file.mkdir();
	}

	public void moveDataFiles()
	{
		copyRawToData( R.raw.asound_default, ASOUND_DEFAULT );
		copyRawToData( R.raw.asound_bluetooth, ASOUND_BT );
		copyRawToData( R.raw.asound_spkrphone, ASOUND_SPKRPHONE );
	}

	public void copyRawToData( int rId, String dest )
	{
		InputStream fin = null;
		FileOutputStream fout = null;
		File outfile = new File( dest );

		try {
			fin = epicBT.this.getResources().openRawResource( rId );
		} catch( Resources.NotFoundException e ) {
			Log.v(LOGTAG, "Couldn't open raw resource: rId, " + e.toString() );
			return;
		}

		try { 
			outfile.createNewFile(); 
		} catch( IOException e ) {
			Log.v(LOGTAG, "Couldn't touch output file: "+outfile+", "+ e.toString() );
			return;
		}

		try {
			fout = new FileOutputStream( outfile );
		} catch( FileNotFoundException e ) {
			Log.v(LOGTAG, "Couldn't open output file: "+outfile+", "+ e.toString() );
			return;
		}

		BufferedInputStream bis = new BufferedInputStream( fin );
		DataInputStream dis = new DataInputStream( bis );
		
		try {

			while ( dis.available() != 0 )
			{
				fout.write(dis.readByte());
			}
		
			fin.close();
			fout.close();
			bis.close();
			dis.close();

		} catch ( IOException e ) {
			Log.v(LOGTAG, "Problem writing to file" + outfile +", "+e.toString());
		}

	}

	private void swapAsound( String source ) throws Exception
	{
		Log.v(LOGTAG, "Swapping asound.conf from "+ source);
		Process process = null;
		
		// sudo make me root
		process = Runtime.getRuntime().exec("su");
		DataOutputStream os = new DataOutputStream(process.getOutputStream());
		
		// Test the /etc/ dir to see if we can write
		if ( !canWriteEtc() )
		{
			// Try to (safely) remount the filesystem
				// If the user is rooted, they most likely have the remount script.
			os.writeBytes("remount rw\n");
			os.flush();
			remountRO = true;
			Log.v(LOGTAG, "/system/ remounted RW");

			//if ( !canWriteEtc() )
			//{	// Not gunna happen.
		//		makeMeToast("Could not access filesystem, failed");
		//		Log.v(LOGTAG, "still couldn't write, failing");
		//		throw new Exception();
		//	}
		}

		// Commands to send to rewrite the alsa config
		String commands[] = {
			"cat "+ source +" > "+ SYS_ASOUND_CONF
			};


		for (String command : commands) 
		{
			os.writeBytes(command+"\n");
		}
		os.flush();

		if ( remountRO ) 
		{
			os.writeBytes("remount ro\n");
			os.flush();
			Log.v(LOGTAG, "/system/ remounted RO");
		}
        os.writeBytes("exit\n");
        os.flush();


        os.close();
		
		process.waitFor();
	}

	private void restartMediaServer() throws Exception
	{  // Restarts mediaserver, duh.
		Log.v(LOGTAG, "restarting mediaserver");
		
		String commands[] = {
			"kill -1 `pidof mediaserver`"
			};
		
		Process process = null;
		process = Runtime.getRuntime().exec("su");
		
		DataOutputStream os = new DataOutputStream(process.getOutputStream());
		for (String command : commands )
		{
			os.writeBytes(command +"\n");
		}
		os.writeBytes("exit\n");
		os.flush();
		os.close();

		process.waitFor();
	}
}
