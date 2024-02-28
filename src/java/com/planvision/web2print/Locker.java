package com.planvision.web2print;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import org.graalvm.polyglot.Value;

import com.planvision.visionr.core.VException;
import com.planvision.visionr.core.api.HostImpl;
import com.planvision.visionr.core.api.RuntimeConfig;
import com.planvision.visionr.core.misc.StringUtils;

public class Locker {
	 private static File workTempWebLockDir = null;
	 public static interface Callback {
		 public Value execute() throws VException;
	 } 
	 public static Value executeInTempFileLock(String key,Callback callback) throws VException {
		 if (workTempWebLockDir == null) {
			 File t = new File(RuntimeConfig.projectDir+"/"+RuntimeConfig.workTempWebLockDirRel);
			 if (!t.exists())
				 t.mkdirs();
			 workTempWebLockDir=t;
		 }
		 File lockf = new File(workTempWebLockDir,StringUtils.escapeFileName(key));
		 try {
	         RandomAccessFile f = new RandomAccessFile(lockf, "rw");
	         try {
		         FileChannel fileChannel = f.getChannel();
		         try {
			         // Try acquiring the lock
			         for (int i=0;i<60*2/* 2 MIN TODO CONFIG OPTION */;i++) {
			                try (FileLock lock = fileChannel.tryLock()) {
			                    if (lock != null) {
			                    	Value res = callback.execute();
			                    	// DONE 
			                    	lock.release();
			                    	return res;
			                    }
			                } catch (IOException e) {
			                	HostImpl.me.getLogger().info("Exec.executeJSWithTempDirLock : wating for lock of "+key+" : "+e.getMessage());
			                	try {
									Thread.sleep(1000);
								} catch (InterruptedException e1) {
									throw new VException(e);
								}
			                }
			         }
			         throw new VException("Timeout waiting for lock "+key);
		         } finally {
		        	 fileChannel.close();
		         }
	         } finally {
	        	 f.close();
	         }
		 } catch (IOException e) {
			 throw new VException(e);
		 }
	 }


}
