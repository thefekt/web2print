package com.planvision.web2print;

import java.io.File;

import com.planvision.visionr.core.CorePrefs;
import com.planvision.visionr.core.api.RuntimeConfig;
import com.planvision.visionr.host.impl.lmdb.CleanupTasksRunner;
import com.planvision.visionr.host.impl.lmdb.tasks.TempDirCleanupThread;
import com.planvision.visionr.host.server.WebServer;
import com.planvision.visionr.host.server.WebServer.CustomUploadAuthenticator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class Bootstrap {
	public static void bootstrap() {
		RuntimeConfig.hostApis.put("web2print.ScribusService",new ScribusService());
		WebServer.addCustomUploadAuthenticator(new CustomUploadAuthenticator() {			
			@Override
			public Boolean isAllowUpload(String sessionId, HttpServletRequest req, HttpServletResponse resp) {
				/* TODO CHECK EXTERNAL AUTH !! IMPORTANT !! */
				return true;
			}
		});
		// image op cache 
		int imgOpCacheKeepMinSizeMB = CorePrefs.getIntPref("web2print.image.operation.cache.min.mb",200);		// 200 mb min
		int imgOpCacheKeepMinSeconds = CorePrefs.getIntPref("web2print.image.operation.cache.min.mb",60*5);	// 5 min min
		// cleanup dir task 
		CleanupTasksRunner.addAdditionalCleanupTaskRunner(new TempDirCleanupThread(new File[] {Utils.tmpDirImgOp,Utils.tmpCachedDir},imgOpCacheKeepMinSizeMB,imgOpCacheKeepMinSeconds));
	}
}