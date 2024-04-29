package com.planvision.web2print;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.CCITTFactory;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.graalvm.polyglot.Value;

import com.planvision.visionr.core.VException;
import com.planvision.visionr.core.api.AccessContext;
import com.planvision.visionr.core.api.HostImpl;
import com.planvision.visionr.core.api.LocalStringObjectCache;
import com.planvision.visionr.core.api.RuntimeConfig;
import com.planvision.visionr.host.server.ResourceCache;
import com.planvision.visionr.host.server.office.PDFStorage;
import com.planvision.visionr.host.core.context.AccessGroupManager;
import com.planvision.visionr.host.core.scripting.core.JSEngine;
import com.planvision.visionr.host.impl.schema.ObjectReference;
import com.planvision.visionr.host.server.FileUtils;

public class Utils {
	
	public static File tmpDirImgOp = new File(RuntimeConfig.projectDir+"/work/tmp/pdfimgop");
	private static String tmpCachedDirStr = RuntimeConfig.projectDir+"/work/tmp/w2p";
	public static File tmpCachedDir = new File(tmpCachedDirStr);
	static {
		tmpDirImgOp.mkdirs();		
		tmpCachedDir.mkdirs();
	}
	
	public static void resetTmpDir() {
		for (File f : tmpCachedDir.listFiles()) if (f.isFile()) 
			f.delete();
	}
	
	public static Value getCachedResult(String key) {
		File f = new File(tmpCachedDir,key);
		if (!f.exists()) 
			return null;
		try {
			String s[] = Files.readString(f.toPath(), StandardCharsets.UTF_8).split("\\n");
			String docUUID = s[0];
			int odid = Integer.parseInt(s[1]);
			long id = Long.parseLong(s[2]);
			String filepath = s[3];
			String regsJSON = s[4];
			//---------------------------------------------------------------------------
			File rf = new File(filepath);
			//---------------------------------------------------------------------------
			if (!HostImpl.me.touchTempFileLastAccessed(rf)) {
				rf.delete();
				return null;
			}
			//---------------------------------------------------------------------------
			Value res = JSEngine.newEmptyObject();
			res.putMember("doc", ObjectReference.make(id, odid).getJSObj());
			res.putMember("regs", JSEngine.jsonParse(regsJSON));
			res.putMember("uuid", docUUID);
			return res;
		} catch (Exception e) {
			f.delete();
			return null;
		}
	}
	
	public static void putCachedResult(String key,File file,ObjectReference ref,Value regions) throws VException {
		Value a = AccessGroupManager.getUserGroupByCode("administrators").getJSObj();
		ref.setJSValRel(ref.getObjectDef().getPropertyByCodeUnsafe("access_owner"),a);
		ref.setJSValRel(ref.getObjectDef().getPropertyByCodeUnsafe("access_read"),a);

		File rf = new File(tmpCachedDir,key);
		StringBuilder sb = new StringBuilder();
		sb.append(ref.getObject().getSimpleValueByCodeUnsafe("uuid"));sb.append("\n");
		sb.append(ref.od.id);sb.append("\n");
		sb.append(ref.id);sb.append("\n");
		try {
			sb.append(file.getAbsolutePath());
			if (rf.exists() && !FileUtils.touchFileLastAccessed(rf)) {
				HostImpl.me.getLogger().error("W2P putCachedResult could not put to cache");
				rf.delete();
				return;
			}
			sb.append("\n");
			sb.append(JSEngine.jsonStringify(regions));
			Files.write(rf.toPath(),sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			HostImpl.me.getLogger().error(e);
		}
	}
	
	public static boolean isImage(String name) {
		if (name.startsWith("@")) return false;
		if (name.endsWith(".png")) return true;
		if (name.endsWith(".jpg")) return true;
		if (name.endsWith(".jpeg")) return true;
		if (name.endsWith(".tiff")) return true;
		if (name.endsWith(".tif")) return true;
		if (name.endsWith(".jp2")) return true;
		if (name.endsWith(".j2k")) return true;
		if (name.endsWith(".svs")) return true;
		if (name.endsWith(".svg")) return true;
		if (name.endsWith(".pdf")) return true;
		if (name.endsWith(".eps")) return true;
		return false;
	}
	protected static void unzip(File zipFile, File dir) {
        // create output directory if it doesn't exist
        if(!dir.exists()) dir.mkdirs();
        FileInputStream fis=null;
        try {
            fis = new FileInputStream(zipFile);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while(ze != null){             
                String fileName = ze.getName();
                File newFile = new File(dir,fileName);
                File copyFile = null;
                if (isImage(fileName)) {
                	copyFile = newFile;
                	newFile = new File(dir,fileName+".ORIG");
                }
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(newFile);
                zis.transferTo(fos);
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                // create a reference to the .ORIG file
                if (copyFile != null)  {
                	copyFile.delete();
                    Files.createLink(copyFile.toPath(), newFile.toPath());
                }
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
        	try  {
            	if (fis != null)
            		fis.close();
        	} catch  (IOException e) {}
        }
    }

	private static LocalStringObjectCache<PDRectangle[]> cachedDimensions = HostImpl.me.newStringObjectLocalSoftCache();
	private static PDRectangle[] getCachedDimensions(File f) {
		String key = f.getName()+f.lastModified();
		PDRectangle []t = cachedDimensions.get(key);
		if (t != null) return t;
		PDDocument doc;
		try {
			doc = Loader.loadPDF(f);
			try {
				PDPage page = doc.getPage(0);
				PDRectangle at[] = new PDRectangle[] {
						page.getMediaBox(),
						page.getCropBox(),
						page.getTrimBox(),
						page.getBleedBox(),
						page.getArtBox()
								};
				cachedDimensions.put(key, at);
				return at;
			} finally {
				doc.close();
			}
		} catch (IOException e) {
			HostImpl.me.getLogger().error(e);
			return null;
		}
		
	}
	public static void copyImageInTempDirectory(String uuid,File outImage,Value region,AccessContext context) throws IOException,VException {

		String ext = outImage.getName().substring(outImage.getName().lastIndexOf('.')+1);
		double rx = region.getMember("x").asDouble();
		double ry = region.getMember("y").asDouble();
		double rw = region.getMember("w").asDouble();
		double rh = region.getMember("h").asDouble();
		double width = region.getMember("dw").asDouble();
		double height = region.getMember("dh").asDouble();
		if (ext.equalsIgnoreCase("PDF")) 
		{
			File r = ResourceCache.getResource("/documents/"+uuid+".uuid",context);
			StringBuilder sb = new StringBuilder();
			sb.append(ext);sb.append("\n");
			sb.append(rx);sb.append("\n");
			sb.append(ry);sb.append("\n");
			sb.append(rw);sb.append("\n");
			sb.append(width);sb.append("\n");
			sb.append(height);sb.append("\n");
			sb.append(r.getCanonicalPath());sb.append("\n");
			sb.append(r.lastModified());
			String key = "OP@"+HostImpl.me.getSHA256(sb.toString()).replace('/','_')+".pdf";
			File cf = new File(tmpDirImgOp,key);
			// CHECK FOR CACHED VERSION (PDF IMAGE OP CACHE)
			if (cf.exists()) {
				try {
					outImage.delete();
		            Files.createLink(outImage.toPath(),cf.toPath());
		            return;
				} catch (IOException e) {
					// reset, delete cached value
					cf.delete();
				}
			}				
			String rext = r.getName().substring(r.getName().lastIndexOf('.')+1);
			// final pdf source is something else? 
			File x = PDFStorage.syncPDF(uuid, rext.toUpperCase(), false, null,context);
			if (x != null) 
				 r=x;
			if (x == null && !isImage(r.getName())){
				HostImpl.me.getLogger().error("Trying to add non image file as image : "+r.getPath()+" >> "+outImage.getPath());
				return;
			}
			// OUTPUT IS PDF 				
			if (x != null || rext.equals("pdf")) {
				// INPUT IS PDF 
				PDDocument newDoc = Loader.loadPDF(r);
				double h = newDoc.getPage(0).getMediaBox().getHeight();

				File of = new File(outImage.getParentFile(),outImage.getName()+".ORIG");
				PDRectangle[] origdims = getCachedDimensions(of);

				copyAndFitPDF(newDoc,origdims,outImage,rx,h-rh-ry,rw,rh); // newDoc closed here
			} else {
				// INPUT IS IMAGE
				PDDocument doc = new PDDocument();
				PDPage page = new PDPage();
		        doc.addPage(page);
			
				PDImageXObject pdImage;
				switch (rext) 
				{
					case "j2k" :
					case "jp2" :
					case "jpg" :
					case "jpeg" :
			            FileInputStream fis = new FileInputStream(r);
			            try {
				            pdImage = JPEGFactory.createFromStream(doc, fis);
			            } finally {
				            fis.close();
			            }
						break;
					case "tif" :
					case "tiff" :
						pdImage = CCITTFactory.createFromFile(doc, r);
						break;
					default : 
						BufferedImage bim = ImageIO.read(r);
			            pdImage = LosslessFactory.createFromImage(doc, bim);
				}
				if (pdImage != null) {
					PDRectangle box=new PDRectangle((float)0,(float)0,pdImage.getWidth(),pdImage.getHeight());
	    			page.setMediaBox(box);
	    			page.setCropBox(box);
	    			page.setTrimBox(box);
	    			page.setArtBox(box);
	    			page.setBleedBox(box);
					PDPageContentStream contents = new PDPageContentStream(doc, page);						
					contents.drawImage(pdImage, 0, 0);	
					contents.close();
					File of = new File(outImage.getParentFile(),outImage.getName()+".ORIG");
					PDRectangle[] origdims = getCachedDimensions(of);
					double coef = origdims[0].getHeight()/rh;
					HostImpl.me.getLogger().warn("COEF "+coef+" | "+origdims[0].getHeight());
					
					//h-rh-ry
					//rh - 
					copyAndFitPDF(doc,origdims,cf,rx,pdImage.getHeight()-rh-ry,rw,rh); // newDoc closed here
					
					// link the cached result as outImage
					outImage.delete();
					Files.createLink( outImage.toPath(),cf.toPath());
		
				}
			}				
		} else {
			String url = "/tmp/documents/"+uuid+".uuid."+ext+"?operation=resizeImage&width="+width+"&height="+height+"&rx="+rx+"&ry="+ry+"&rw="+rw+"&rh="+rh;
			File rf = null;
			try {
				rf = ResourceCache.getResource(url,context);
			} catch (VException e) {
				HostImpl.me.getLogger().error("Unable to resolve IMAGE by url | "+url+"\nException : "+e);
			}
			if (rf == null) {
				HostImpl.me.getLogger().error("Can not copy image uuid @ "+uuid);
				return;
			}
			if (!isImage(rf.getName())){
				HostImpl.me.getLogger().error("Trying to add non image file as image : "+rf.getPath()+" >> "+outImage.getPath());
				return;
			}
			FileUtils.copyFile(rf, outImage);
		}
	}
	private static void copyAndFitPDF(PDDocument newDoc, PDRectangle[] origdims,File outImage,double rx,double ry,double rw,double rh) throws IOException {
		PDPage newPage = newDoc.getPage(0);
		for (int i=newDoc.getNumberOfPages()-1;i>=1;i--)
			newDoc.removePage(i);
		PDRectangle box=new PDRectangle((float)rx, (float)ry, (float)rw, (float)rh);
    	PDRectangle newMediaBox = box;
    	PDRectangle targetMediaBox = origdims[0];
    	// Calculate scale factors
         float scale = targetMediaBox.getHeight() / newMediaBox.getHeight();
         // Calculate translation to align the origins of the original and target media boxes
         float translateX = targetMediaBox.getLowerLeftX() - newMediaBox.getLowerLeftX() * scale;
         float translateY = targetMediaBox.getLowerLeftY() - newMediaBox.getLowerLeftY() * scale;
         newPage.setMediaBox(origdims[0]);
         newPage.setCropBox(origdims[1]);
         newPage.setTrimBox(origdims[2]);
         newPage.setArtBox(origdims[3]);
         newPage.setBleedBox(origdims[4]);

		transformPage(newDoc,newPage,scale,scale,translateX,translateY);
		outImage.delete();
		newDoc.save(outImage);
		newDoc.close();
	}
	public static void restoreLinks(File outdir) throws IOException {
		for (File orig : outdir.listFiles()) if (orig.getName().endsWith(".ORIG")) {
			File copyFile = new File(orig.getParent(),orig.getName().substring(0,orig.getName().length()-5));
			if (!copyFile.exists() || copyFile.delete())
				Files.createLink(copyFile.toPath(), orig.toPath());
		}
	}

    private static void transformPage(PDDocument doc,PDPage page,double scaleX,double scaleY,double translateX,double translateY) throws IOException {
     	PDFStreamParser parser = new PDFStreamParser(page);              
		List tokens = parser.parse();         

		// Scaling
		tokens.add(0, new COSFloat((float)scaleX)); // a
		tokens.add(1, COSInteger.ZERO);             // b
		tokens.add(2, COSInteger.ZERO);             // c
		tokens.add(3, new COSFloat((float)scaleY)); // d

		// Translation (offset)
		tokens.add(4, new COSFloat((float)translateX)); // tx
		tokens.add(5, new COSFloat((float)translateY)); // ty

		tokens.add(6,Operator.getOperator("cm"));              
		PDStream newContents = new PDStream( doc );          
		OutputStream os = newContents.createOutputStream();
		try {
			ContentStreamWriter writer = new ContentStreamWriter(os);
			writer.writeTokens( tokens );              
			page.setContents(newContents);
		} finally {
			os.close();
		}
    }
	
    public static boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i=0; i<children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    return false;
                }
            }
        }    
        // The directory is now empty, delete it
        return dir.delete();
    }


	 static double[] getPDFDimensions(File file) {
		 try {
            PDDocument document = Loader.loadPDF(file);
            try {
                if (document == null || document.getNumberOfPages() < 1) return null;
                PDRectangle mb = document.getPage(0).getMediaBox();
                return new double[] {mb.getWidth(),mb.getHeight()};
            } finally {
            	document.close();
            }
		 } catch (IOException e) {
			 HostImpl.me.getLogger().error(e);
			 return null;
		 }
	 }

}
