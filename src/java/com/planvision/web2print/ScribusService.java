package com.planvision.web2print;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.io.FileUtils;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.carrotsearch.hppcrt.maps.ObjectLongHashMap;
import com.planvision.visionr.core.CorePrefs;
import com.planvision.visionr.core.VException;
import com.planvision.visionr.core.api.FileAndWebPath;
import com.planvision.visionr.core.api.HostImpl;
import com.planvision.visionr.core.api.RuntimeConfig;
import com.planvision.visionr.core.misc.StringUtils;
import com.planvision.visionr.core.schema.DBModule;
import com.planvision.visionr.core.schema.DBObjectDef;
import com.planvision.visionr.host.JavaHost;
import com.planvision.visionr.host.core.context.VRSessionContext;
import com.planvision.visionr.host.core.scripting.api.Common;
import com.planvision.visionr.host.core.scripting.api.Require;
import com.planvision.visionr.host.core.scripting.api.VSC;
import com.planvision.visionr.host.core.scripting.core.JSEngine;
import com.planvision.visionr.host.core.scripting.core.api.export.Excel;
import com.planvision.visionr.host.core.scripting.oscript.api.io.TmpFile;
import com.planvision.visionr.host.core.scripting.oscript.api.reports.BarCode;
import com.planvision.visionr.host.impl.ImageUtils;
import com.planvision.visionr.host.impl.schema.DBObjectDefImpl;
import com.planvision.visionr.host.impl.schema.ObjectReference;
import com.planvision.visionr.host.master.JSConverter;
import com.planvision.web2print.Locker.Callback;

//Servicing TMS like request for document preview with OpenSeaDragon
public class ScribusService {

	private static String _executable = null;

	private static class ProcessInfo {
		public Process process = null;
		public OutputStreamWriter clientSocketWriter;
		public BufferedReader clientSocketReader;
		public Socket clientSocket;
		public int port;

		public void newPort() {
			while (true) {
				int tp = 14000 + (int) (Math.random() * 10000);
				try {
					ServerSocket s = new ServerSocket(tp);
					s.close();
					port = tp;
					break;
				} catch (IOException ex) {
					continue; // try next port
				}
			}
		}

		public ProcessInfo() {
			newPort();
		}
	}

	private static HashMap<String, ProcessInfo> processes = new HashMap();
	private static String getExecutable() {
		if (_executable != null)
			return _executable;
		_executable = CorePrefs.getStrPref("scribus.dir");
		if (_executable == null) {
			// ENV PATH
			if (RuntimeConfig.isWin) {
				_executable = "scribus.exe";
			} else if (RuntimeConfig.isMac) {
				_executable = "scribus";
			} else {
				_executable = "scribus";
			}
		} else {
			if (RuntimeConfig.isWin) {
				_executable+= "/scribus.exe";
			} else if (RuntimeConfig.isMac) {
				_executable+="/scribus";
			} else {
				_executable+="/scribus";
			}
		}
		return _executable;
	}

	// -----------------------------------------------------------------------------
	@HostAccess.Export 
	public void forceStop(String tmpDirKey) {
		ProcessInfo pi = checkProcess(tmpDirKey, false);
		if (pi == null)
			return;
		if (pi.process != null) {
			pi.process.destroyForcibly();
			pi.process = null;
		}
		pi.clientSocket = null;
		pi.clientSocketWriter = null;
		pi.clientSocketReader = null;
		pi.newPort();
	}

	private static Value returnFileAsFocument(File file, String name) throws VException {
		String extension = file.getName().substring(file.getName().lastIndexOf('.')+1);
		FileAndWebPath uf = JavaHost.me.createTmpFile(extension); 
		file.renameTo(new File(uf.absolutePath));
		String documentCode = StringUtils.randomHex16TS()+"."+extension;
		Value res = Require.root.require("server/runtime/documents").getMember("moveToUpload").execute(uf.webpath,documentCode,name,extension);
		return res;
	}

	private static Value returnErrorAsDocument(File ofile, String name) throws VException {
		try {
			FileUtils.copyFile(new File(RuntimeConfig.projectDir+"/src/python/internal-error.pdf"), ofile);
		} catch (IOException e) {
			HostImpl.me.getLogger().error(e);
		}
		return returnFileAsFocument(ofile, name);
	}

	@HostAccess.Export 
	public Value getAvailableContents(String tmpDirKey) {
		try {
		    String path = resolve(tmpDirKey,"template.sla");
		     
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document document = builder.parse(new File(path));
			Vector<Map<String, Object>> entries = new Vector();
			HashSet<String> used = new HashSet();

			HashMap<String, Boolean> tables = new HashMap();
			HashMap<String, HashMap> tablesData = new HashMap();
			HashMap<String, Integer> tableMaxRow = new HashMap();
			HashMap<String, Integer> tableMaxCol = new HashMap();

			extractContents(tmpDirKey, document.getFirstChild(), used, entries, tables, tablesData, tableMaxRow,
					tableMaxCol,null);
			return JSConverter.VR2JS(entries);
		} catch (Exception e) {
			HostImpl.me.getLogger().error(e);
			return null;
		}
	}

	private static void extractContents(String tmpDirKey, Node n, HashSet<String> used,
			Vector<Map<String, Object>> entries, HashMap<String, Boolean> tables, HashMap<String, HashMap> tablesData,
			HashMap<String, Integer> tableMaxRow, HashMap<String, Integer> tableMaxCol,Integer page) {
		if (n.getNodeName().equals("PAGEOBJECT")) {
			Node ownp = n.getAttributes().getNamedItem("OwnPage");
			if (ownp != null) {
				try {					
					page = Integer.parseInt(ownp.getNodeValue())+1;
				} catch (NumberFormatException e) {
					HostImpl.me.getLogger().warn("ScribusService.extractContents : can not parse PAGEOBJECT OwnPage attribute : "+n.toString());
				}
			}
		}
		NodeList cn = n.getChildNodes();
		if (cn != null) {
			int l = cn.getLength();
			for (int i = 0; i < l; i++)
				extractContents(tmpDirKey, cn.item(i), used, entries, tables, tablesData, tableMaxRow, tableMaxCol,page);
		}
		String t = n.getNodeValue();
		if (t != null) {
			t = t.trim();
			if (t.startsWith("{{") && t.endsWith("}}")) {
				String v = t.substring(2, t.length() - 2);
				HashMap<String, Object> a = new HashMap();
				a.put("type", "varchar");
				if (page != null)
					a.put("page", page);
				a.put("code", v);
				entries.add(a);
			}
		}
		NamedNodeMap at = n.getAttributes();
		if (at != null) {
			int l = at.getLength();
			for (int i = 0; i < l; i++) {
				Node a = at.item(i);
				String bt = a.getNodeValue();
				if (bt != null && bt.startsWith("{{") && bt.endsWith("}}")) {
					String ov = bt.substring(2, bt.length() - 2);
					if (!ov.isEmpty() && used.add(ov)) {
						int k = ov.indexOf(".");
						if (k < 0) {
							HashMap<String, Object> va = new HashMap();
							va.put("type", "varchar");
							va.put("code", ov);
							if (page != null)
								va.put("page", page);
							entries.add(parseInputField(ov,page));
						} else {
							String tbl = ov.substring(0, k);
							String v = ov.substring(k + 1);
							if (v.length() > 0 && v.charAt(0) >= 'A' && v.charAt(0) <= 'Z'
									&& v.substring(1).matches("-?\\d+") /* Integer */) {
								int row = (v.charAt(0) - 'A');
								int col = Integer.parseInt(v.substring(1)) - 1;
								if (!tables.containsKey(tbl)) {
									File fe = new File(resolve(tmpDirKey,tbl + ".xlsx"));
									if (!fe.exists() || !fe.canRead())
										fe = new File(resolve(tmpDirKey,tbl + ".xls"));
									boolean x = fe.exists() && fe.canRead();
									tables.put(tbl, x);
									if (x) {
										HashMap<String, Object> va = new HashMap();
										va.put("type", "table");
										va.put("code", tbl);
										if (page != null)
											va.put("page", page);
										org.graalvm.polyglot.Value res = JSConverter.VR2JS((new Excel()).readExcelData(fe, true));
										String jsonData = JSEngine.jsonStringify(res).asString();
										va.put("data", jsonData);
										entries.add(va);
										tablesData.put(tbl, va);
									}
								}
								boolean isTbl = tables.get(tbl);
								if (isTbl) {
									// table cell
									if (!tableMaxRow.containsKey(tbl) || tableMaxRow.get(tbl) < row)
										tableMaxRow.put(tbl, row);
									if (!tableMaxCol.containsKey(tbl) || tableMaxCol.get(tbl) < col)
										tableMaxCol.put(tbl, col);
									HashMap<String, Object> vk = tablesData.get(tbl);
									vk.put("columns", tableMaxCol.get(tbl) + 1);
									vk.put("rows", tableMaxRow.get(tbl));
								} else {
									// not a table cell
									entries.add(parseInputField(ov,page));
								}
							} else {
								// not a table cell
								entries.add(parseInputField(ov,page));
							}
						}
					}
				}
				if (a.getNodeName().equalsIgnoreCase("PFILE")) {
					String v = a.getNodeValue();
					if (v != null) {
						v = v.trim();
						if (!v.isEmpty() && !v.startsWith("@")) {
							String path = resolve(tmpDirKey,v);
							String lw = path.toLowerCase();
							if (lw.endsWith(".jpg") || lw.endsWith(".jpeg") || lw.endsWith(".png")
									|| lw.endsWith(".pdf")) {
								if (used.add(path)) {
									HashMap<String, Object> x = new HashMap();
									x.put("type", "image");
									x.put("code", v);
									if (page != null)
										x.put("page", page);
									File f = new File(path);
									if (f.getName().startsWith("QR."))
										x.put("type", "qrcode");
									else
										x.put("file", f.getAbsolutePath());
									if (f.exists() && f.canRead()) {
										double dims[] =
												f.getName().endsWith(".pdf")  ? Utils.getPDFDimensions(f) : // do not use visionr host impl, because pdfium will block the file (win32)
												HostImpl.me.getImageDimensionsSystem(f);
										if (dims != null && dims[0] > 0 && dims[1] > 0) {
											double prop = ((double) dims[0]) / ((double) dims[1]);
											x.put("proportion", prop);
										}
									}
									entries.add(x);
								}
							}
						}
					}
				}
			}
		}
	}
	private static HashMap<String, Object> parseInputField(String code,Integer page) {
		HashMap<String, Object> va = new HashMap();
		if (code.startsWith("COLOR") || code.startsWith("CL."))
			va.put("type", "indexed_color");
		else if (code.startsWith("TX."))
			va.put("type", "text");
		else if (code.startsWith("DT."))
			va.put("type", "date");
		else if (code.startsWith("TM."))
			va.put("type", "time");
		else if (code.startsWith("TS."))
			va.put("type", "datetime");
		else if (code.startsWith("IN."))
			va.put("type", "integer");
		else if (code.startsWith("FP."))
			va.put("type", "double");
		else 
			va.put("type", "varchar");
		va.put("code", code);
		if (page != null)
			va.put("page", page);
		return va;
	}

	private static ProcessInfo checkProcess(String key, boolean doStart) {
		ProcessInfo pi;
		synchronized (processes) {
			pi = processes.get(key);
			if (pi == null) {
				pi = new ProcessInfo();
				processes.put(key, pi);
			}
		}
		if (pi.process != null && !pi.process.isAlive())
			pi.process = null;
		if (pi.process == null) {
			if (!doStart)
				return null;
			ProcessBuilder pb = new ProcessBuilder();
			// --no-gui -ns -g -py scrbserv2.py -pa 9988 -- good1.sla
			String pySrc = RuntimeConfig.projectDir+"/src/python/scribserv.py";

			HostImpl.me.getLogger().warn(">>>> \"" + getExecutable() + "\" \"" + resolve(key,"template.sla")+"\" --no-gui -ns -g -py \"" + pySrc + "\" " + pi.port);

			// xvfb-run REQUIRED ON LINUX/UNIX PLATFORMS
			if (RuntimeConfig.isWin || RuntimeConfig.isMac) {
				pb.command(getExecutable(), resolve(key,"template.sla"),
						"--no-gui", "-ns", "-g", "-py", pySrc, "" + pi.port);
			} else {
				pb.environment().put("DISPLAY", ":0");
				pb.command(getExecutable(), resolve(key,"template.sla"),
						"--no-gui", "-ns", "-g", "-py", pySrc, "" + pi.port);
			}
			try {
				File nil;
				if (RuntimeConfig.isWin) {
					nil = new File("NUL:");
				} else {
					nil = new File("/dev/null");
				}
				pb.redirectOutput(nil);
				pb.redirectError(nil);
				pi.process = pb.start();
			} catch (IOException e) {
				HostImpl.me.getLogger().error(e);
				return null;
			}

		}
		synchronized (pi.process) {
			for (int i = 0; i < 30; i++) {
				try {
					if (!pi.process.isAlive())
						return checkProcess(key, doStart);
					if (pi.clientSocket == null || pi.clientSocket.isClosed()) {
						pi.clientSocket = new Socket("127.0.0.1", pi.port);
						pi.clientSocketWriter = new OutputStreamWriter(pi.clientSocket.getOutputStream(), StandardCharsets.UTF_8);
						pi.clientSocketReader = new BufferedReader(
								new InputStreamReader(pi.clientSocket.getInputStream(), StandardCharsets.UTF_8));
						HostImpl.me.getLogger().warn("ScribusService CONNECTED!");
					}
					return pi;
				} catch (IOException e) {
					HostImpl.me.getLogger().warn("ScribusService not started..waiting...");
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e1) {
						HostImpl.me.getLogger().error(e1);
						return null;
					}
					continue;
				}
			}
		}
		return null;
	}
	// ---------------------------------------------------------------------
	@HostAccess.Export 
	public Value initTemplateContents(Value tmpl) throws VException {
		/*VSQLCompiledCode nparent = HostImpl.me.compileCachedVSQL(DBModule.g("documents").getSchemaByCode("folder"), "SELECT id,objectdef WHERE path = :PATH");
		ParamsHash params = new ParamsHash();
		String dirp = "/web2print/"+tmpl.getMember("uuid").asString()+"/defaults";
		params.put("PATH", dirp);
		Value dirr;
		VResultSet rs = HostImpl.me.getConnection().executeQuery(nparent,params);
		try {
			if (!rs.next()) throw new VException("Unable to find defaults directory : "+dirp);
			dirr=ObjectReference.make(rs.getLong(1), rs.getInt(2)).getJSObj();
		} finally {
			rs.close();
		}*/
		final String key =  "rndr-"+tmpl.getMember("document").getMember("id").asLong();
		 Callback cb = new Callback() {
			@Override
			public Value execute() throws VException {
				/*File outdir = */syncTemplate(tmpl);
				Value contents = getAvailableContents(key);
				if (JSEngine.isInstance(tmpl)) {
					Value x = tmpl.getMember("contents");
					if (!x.isNull()) {
						int sz = (int)x.getArraySize();
						for (int i=0;i<sz;i++) {
							Value o = x.getArrayElement(i);
							o.getMember("delete").execute();
							o.getMember("commit").execute();
						}
					}
				}
				Value arr = JSEngine.newEmptyArray();
				int sz = (int)contents.getArraySize();
		        for (var i=0;i<sz;i++) 
		        {
		        	Value e = contents.getArrayElement(i);
		        	String type = e.getMember("type").asString();
                    String code = e.getMember("code").asString();
                    Value p = e.getMember("page");
		            switch (type) 
		            {
		            	case "integer" : 
		            	case "double" : 
		            	case "datetime" : 
		            	case "time" : 
		            	case "date" : 
		            	case "text" : 
		                case "varchar" : {
		                	Value c = _genCommonInput(code,type,p,tmpl);
		                	Value cc = c.getMember("commit");
			                if (cc != null) cc.execute();
		                    arr.setArrayElement(arr.getArraySize(),c);
		                    break; }
		                case "indexed_color" : {
		                    Value c;
		                    if (JSEngine.isInstance(tmpl)) {
		                    	c = ((DBObjectDefImpl)DBModule.g("web2print").getSchemaByCode("indexed_color_content").impl()).getJSProxy().newInstance();
		                    	c.putMember("template", tmpl);
		                    } else {
		                    	c = JSEngine.newEmptyObject();
		                    	c.putMember("type",type);
		                    }
		                    c.putMember("code", code);
		                    c.putMember("dest_page", p);
		                    c.putMember("initial_value", "CMYK(0,30,100,0)");
		                    Value cc = c.getMember("commit");
		                    if (cc != null) cc.execute();
		                    arr.setArrayElement(arr.getArraySize(),c);
		                    break; }		                
		                case "image" : {
		                    Value c;
		                    boolean isi;
		                    if (isi=JSEngine.isInstance(tmpl)) {
		                    	c = ((DBObjectDefImpl)DBModule.g("web2print").getSchemaByCode("image_content").impl()).getJSProxy().newInstance();
		                    	c.putMember("template", tmpl);
		                    } else {
		                    	c = JSEngine.newEmptyObject();
		                    	c.putMember("type",type);
		                    }
		                    c.putMember("code", code);
		                    c.putMember("dest_page", p);
		                    c.putMember("proportion", e.getMember("proportion"));
		                    
		                    Value f = e.getMember("file");
		                    if (f != null && !f.isNull() && isi) {
		                    	String fpath = f.asString();
		                    	TmpFile a = new TmpFile(fpath.substring(fpath.lastIndexOf('.')+1));
		                    	try {
			                    	try {
										FileUtils.copyFile(new File(fpath),a.getFile());
				                    	Value doc = VSC.callVSC("doc.misc.uploadTemp",new Value[] { JSConverter.VR2JS(code),JSEngine.UNDEFINED,JSConverter.VR2JS(a.getExtension()),JSEngine.UNDEFINED/*description*/,JSConverter.VR2JS(a)});
				                    	//doc.putMember("parent",dirr);
				                    	doc.getMember("commit").execute();
				                    	c.putMember("initial_value", doc);
			                    	} catch (VException ex) {
			                    		HostImpl.me.getLogger().error("Error uploading default image as document "+ex);
			                    	}
								} catch (IOException e1) {
									HostImpl.me.getLogger().error(e1);
								}
		                    }
		                    Value cc = c.getMember("commit");
		                    if (cc != null) cc.execute();
		                    arr.setArrayElement(arr.getArraySize(),c);
		                    break; }
		                case "table" : {
		                	Value c;
		                    if (JSEngine.isInstance(tmpl)) {
		                    	c = ((DBObjectDefImpl)DBModule.g("web2print").getSchemaByCode("table_content").impl()).getJSProxy().newInstance();
		                    	c.putMember("template", tmpl);
		                    } else {
		                    	c = JSEngine.newEmptyObject();
		                    	c.putMember("type",type);
		                    }
		                    c.putMember("code", code);
		                    c.putMember("dest_page", p);
		                    c.putMember("column_count", e.getMember("columns"));
		                    c.putMember("table_data", e.getMember("data"));
		                    c.putMember("row_count", e.getMember("rows"));

		                    Value cc = c.getMember("commit");
		                    if (cc != null) cc.execute();
		                    arr.setArrayElement(arr.getArraySize(),c);
		                    break; }
		                case "qrcode" : {
		                	Value c;
		                    if (JSEngine.isInstance(tmpl)) {
		                    	c = ((DBObjectDefImpl)DBModule.g("web2print").getSchemaByCode("qrcode_content").impl()).getJSProxy().newInstance();
		                    	c.putMember("template", tmpl);
		                    } else {
		                    	c = JSEngine.newEmptyObject();
		                    	c.putMember("type",type);
		                    }
		                    c.putMember("code", code);
		                    c.putMember("dest_page", p);
		                    c.putMember("initial_value", "http://boss.4ou");
		                    Value cc = c.getMember("commit");
		                    if (cc != null) cc.execute();
		                    arr.setArrayElement(arr.getArraySize(),c);
		                    break; }		                
		                default : 
		                	HostImpl.me.getLogger().warn("!!!TODO!!! initTemplateContents for type "+type);
		            }
	            }
		        if (arr.getArraySize()>0)
		            tmpl.putMember("contents",arr);
                Value cc = tmpl.getMember("commit");
                if (cc != null) cc.execute();
		        return tmpl;
			}
		 };
		 return Locker.executeInTempFileLock(key,cb);
	}
	
	@HostAccess.Export 
	public Value getTemplateJSON(Value tmpl,Value data) throws VException {
		return _getTemplateJSON(tmpl,data,null);
	}
	
	private Value _genCommonInput(String code,String type,Value page,Value tmpl) {
        Value c;
        if (JSEngine.isInstance(tmpl)) {
        	c = ((DBObjectDefImpl)DBModule.g("web2print").getSchemaByCode(type+"_content").impl()).getJSProxy().newInstance();
        	c.putMember("template", tmpl);
        } else {
        	c = JSEngine.newEmptyObject();
        	c.putMember("type",type);
        }
        c.putMember("code", code);
        c.putMember("dest_page", page);
        switch (type) {
        	case "double":
        		c.putMember("initial_value", 1);
        		break;
        	case "integer":
        		c.putMember("initial_value", 1);
        		break;
        	case "time":
        	case "date":
        	case "datetime":
        		c.putMember("initial_value", new Date()); // TODO?
        		break;
        	default :
        		c.putMember("initial_value", code);
        }
        Value cc = c.getMember("commit");
        if (cc != null) cc.execute();
        return c;
	}

	private Value _getTemplateJSON(Value tmpl,Value data,File tmpDir) throws VException {
		boolean doNotRender = tmpDir == null;
		Value toReplace = JSEngine.newEmptyObject();
		Value contents = tmpl.getMember("contents");
		int sz = contents.isNull() ? 0 : (int)contents.getArraySize();
		for (int i=0;i<sz;i++) {
			Value e = contents.getArrayElement(i);
			String code = e.getMember("code").asString();
			String type = e.hasMember("type") ? e.getMember("type").asString() : null;
	        if ("varchar".equals(type) || inherits(e,"web2print","varchar_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	toReplace.putMember(code,vd);
	        } else if ("text".equals(type) || inherits(e,"web2print","text_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	toReplace.putMember(code,vd);
	        } else if ("date".equals(type) || inherits(e,"web2print","date_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	if (vd == null || vd.isNull())
	        		toReplace.putMember(code,"");
	        	else
	        		toReplace.putMember(code,
	        				Common.convertValueByCoreFormat("default_output_format_date",JSConverter.JS2VR(vd),null));
	        } else if ("datetime".equals(type) || inherits(e,"web2print","datetime_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	if (vd == null || vd.isNull())
	        		toReplace.putMember(code,"");
	        	else
	        		toReplace.putMember(code,
	        				Common.convertValueByCoreFormat("default_output_format_datetime_hour_minutes",JSConverter.JS2VR(vd),null));
	        } else if ("time".equals(type) || inherits(e,"web2print","time_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	if (vd == null || vd.isNull())
	        		toReplace.putMember(code,"");
	        	else
	        		toReplace.putMember(code,
	        				Common.convertValueByCoreFormat("default_output_format_hours_minutes",JSConverter.JS2VR(vd),null));
	        } else if ("integer".equals(type) || inherits(e,"web2print","integer_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	if (vd == null || vd.isNull())
	        		toReplace.putMember(code,"");
	        	else
	        		toReplace.putMember(code,
	        				Common.convertValueByCoreFormat("output_format_integer_separator",JSConverter.JS2VR(vd),null));
	        } else if ("double".equals(type) || inherits(e,"web2print","double_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	if (vd == null || vd.isNull())
	        		toReplace.putMember(code,"");
	        	else
	        		toReplace.putMember(code,
	        				Common.convertValueByCoreFormat("default_output_format_double",JSConverter.JS2VR(vd),null));
	        } else if ("image".equals(type) || inherits(e,"web2print","image_content")) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	            Value region = JSEngine.UNDEFINED;
	            Value disabled = null;
	            if (vd != null && !vd.isNull()) {
	            	disabled = vd.getMember("disabled");
	            	region = vd.getMember("region");
	            	vd = vd.getMember("document");
	            }
	            if (inherits(vd,"documents","file")) {
	            	DBObjectDef ko = DBObjectDef.g(vd.getMember("SCHEMA").getMember("KEY").asString());
	            	if (DBModule.g("documents").getSchemaByCode("file").allInheritedFromContains(ko)) {
	            		// instanceof od documents file
	                	boolean isDisabled = disabled != null && disabled.asBoolean();
	                	// KEY for image tag 
	            		if (tmpDir == null) {
	            			// during cache key calc, not during render (do not add hashes to the real replace set)
		                	long k1 = 0;
		                	Value t = vd.getMember("update_time");
		                	if (t.isNull()) t = vd.getMember("insert_time");
		                	if (t.isDate()) k1 = t.as(Date.class).getTime();
		                	String key = HostImpl.me.getSHA256(vd.getMember("id").asLong()+'_'+k1+'_'+(isDisabled ? '1' : '0')+'_'+JSEngine.jsonStringify(region).asString());
		                    toReplace.putMember(code,key);
	            		}
	                    //---------------------------
	            		if (!doNotRender) {
		                	Value ve = vd.getMember("get_file").execute();
		                	if (ve.isString()||ve.isNull()) {
		                		// DELETED FILE TODO CLEANUP + ERROR LOG
		                		continue;
		                	}
		                	if (isDisabled) {
		                    	File outImage = new File(tmpDir,code);
		                		String ext = outImage.getName().substring(outImage.getName().lastIndexOf('.')+1);
		                		if (ext.equalsIgnoreCase("PDF")) {
		                			 try {
										Files.write(outImage.toPath(), emptyPDF);
									} catch (IOException e1) {
										HostImpl.me.getLogger().error(e1);
									}
		                		} else {
		                			// PDF 
		                			try {
										Files.write(outImage.toPath(), emptyPNG);
									} catch (IOException e1) {
										HostImpl.me.getLogger().error(e1);
									}
		                		}
		                	} else {
		           		     	com.planvision.visionr.host.core.scripting.oscript.api.io.File file = 
		           		     			ve.as(com.planvision.visionr.host.core.scripting.oscript.api.io.File.class);
			                    if (region == null || region.isNull()) {
			                    	if (!Utils.isImage(file.getName())){
			                    		HostImpl.me.getLogger().error("Trying to add non image file as image : "+file.getPath()+" >> "+new File(tmpDir,code).getPath());
			                    	} else {
				           		     	try {
											FileUtils.copyFile(file.getFile(), /* dest */new File(tmpDir,code));
										} catch (IOException e1) {
											HostImpl.me.getLogger().error(e1);;
										}
			                    	}
			                    } else {
			                    	File outImage = new File(tmpDir,code);
			           		     	try {
										Utils.copyImageInTempDirectory(vd.getMember("uuid").asString(),outImage /* dest */,region,VRSessionContext.accessContext);
									} catch (IOException e1) {
										throw new VException(e1);
									}
			                        //JSCORE.Exec.callVSC("java.util.copyImageInTempDirectory","rndr-"+tmpl.document.id, d, e.code,region);
			                    }
		                	}
		                } 
	            	}
	            }
	            /*{
	              console.warn("render.js : document [" + e.code + "] is not a db.documents.file instance!");
	            }*/
	        } else if ("qrcode".equals(type) || inherits(e,"web2print","qrcode_content") ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
	        		vd = e.getMember("initial_value");
	        	if (vd != null && !vd.isNull()) {
	                if (!doNotRender) { // TODO JPG NOT ONLY PNG | FORMAT CHANGE NOT WORKING | TODO 
		        		if (code.endsWith(".png")) {
		        			// common
			        		byte[] qd = BarCode.encode(vd.asString(),"QR_CODE",256,256,"#000000","#00000000"/* alpha */);
				        	try {
								Files.write(/* dest */new File(tmpDir,code).toPath(),qd);
							} catch (IOException e1) {
								throw new VException(e1);						}
		        		} else {
		        			// fallback
			        		byte[] qd = BarCode.encode(vd.asString(),"QR_CODE",256,256,"#000000","#FFFFFF"/* no alpha */);
			        		BufferedImage img = ImageUtils.loadAsBufferedImage(qd,BufferedImage.TYPE_INT_RGB);
		        			ImageUtils.writeImage(img, new File(tmpDir,code), code.substring(code.lastIndexOf('.')+1));
		        		}
	                } else {
	                	toReplace.putMember(code,vd);
			        }
	        	}
	        } else if ("indexed_color".equals(type) || inherits(e,"web2print","indexed_color_content")  ) {
	        	Value vd = data.getMember(code);
	        	if (vd == null || vd.isNull())
                	toReplace.putMember(code,"");
	        	else {
	        		String d = vd.asString();
		            var h = d.toUpperCase().indexOf(" RGB");
		            if (h > 0) d=d.substring(0,h);
                	toReplace.putMember(code,vd);
	        	}
	        } else if ("table".equals(type) || inherits(e,"web2print","table_content")  ) {
	        	 Value atd = e.getMember("table_data");
	             if (!atd.isNull()) {
	            	 Value _td = JSEngine.jsonParse(atd.asString());
	            	 Value td = JSConverter.transportJSON2JS(_td);
	                 Value columns = td.getMember("columns");
	                 Value bdata = data.getMember(code);
	                 if (bdata == null || bdata.isNull())
	                	 bdata = td.getMember("data");
	                 for (int row=0;row<(int)bdata.getArraySize();row++) {
	                     Value r = bdata.getArrayElement(row);
	                     for (var col=0;col<(int)r.getArraySize();col++) {
	                         Value val = r.getArrayElement(col);
	                         String key = code+"."+(char)(65+col)+(row+1);
	                         Value cd = columns.getArrayElement(col);
	                         String ctype = cd.getMember("type").asString();
	                         Value t = cd.getMember("suffix");
	                         String suffix = t != null && !t.isNull() ? t.asString(): "";
	                         t = cd.getMember("prefix");
	                         String prefix = t != null && !t.isNull() ? t.asString(): "";
	                         if (!suffix.isEmpty()) suffix=" "+suffix;
	                         if (!prefix.isEmpty()) prefix+=" ";
	                         if (!val.isNull())
	                         {
	                        	 Object v;
	                             switch (ctype)
	                             {
	                                 case "double" :
	                                	v=Common.convertValueByCoreFormat("default_output_format_double",JSConverter.JS2VR(val.asDouble()),null);
	                                    break;
	                                 case "integer":
		                                v=Common.convertValueByCoreFormat("default_output_format_integer",JSConverter.JS2VR(val.asLong()),null);
	                                    break;
	                                 case "time":
			                            v=Common.convertValueByCoreFormat("default_output_format_time",JSConverter.JS2VR(val.as(Date.class)),null);
	                                    break;
	                                 case "hoursMinutes" :
				                        v=Common.convertValueByCoreFormat("default_output_format_hours_minutes",JSConverter.JS2VR(val.as(Date.class)),null);
	                                    break;
	                                 case "date" :
					                    v=Common.convertValueByCoreFormat("default_output_format_date",JSConverter.JS2VR(val.as(Date.class)),null);
	                                    break;
	                                 case "datetime" :
	                                 case "datetimeHoursMinutes" :
						                v=Common.convertValueByCoreFormat("default_output_format_datetime_hour_minutes",JSConverter.JS2VR(val.as(Date.class)),null);
						                if (v == null)
						                	v="";
	                                    break;
	                                default :
	                                	v=val.isNull() ? "" : val.getMember("toString").execute().asString();
	                            }
	                            String sval = prefix+v+suffix;
	                            //log.warn(type+" | "+key+" | "+sval);
	                            toReplace.putMember(key,sval);
	                        } else {
	                            toReplace.putMember(key,"");
	                        }
	                     }
	                 }
	             }
	        }
	    }
	    return toReplace;
	}
	
	
	@HostAccess.Export 
	public Value renderTemplate(Value tmpl,Value data) throws VException {
		// check for cached document
		final String ckey = getCachedResultKey(tmpl,data);
		ObjectReference cref = Utils.getCachedResult(ckey);
		if (cref != null) {
			Value jso = cref.getJSObj();
			String duuid = jso.getMember("uuid").asString();
			// ADD TEMP READ 
			HostImpl.me.addDocumentTempReadAccessSession(null/*current session*/, duuid);
			return jso;
		}
		//------------------------------------------------------------------------------
		final String key =  "rndr-"+tmpl.getMember("document").getMember("id").asLong();
		Callback cb = new Callback() {
			@Override
			public Value execute() throws VException {
				String name = tmpl.hasMember("name") ? tmpl.getMember("name").asString() : null;
				String code = tmpl.getMember("code").asString(); 
				File tmpdir = syncTemplate(tmpl);
				Value toReplace = _getTemplateJSON(tmpl,data,tmpdir);
				String json = JSEngine.jsonStringify(toReplace).asString();
				Value result =  convert(key,(name == null ? code : name),json);
		        if (result == null || result.isNull()) {
		            HostImpl.me.getLogger().warn("render.js: Scribus generator returned FIRST EMPTY result : "+code);
		            result = convert(key,tmpl.getMember("toString").execute().asString(),JSEngine.jsonStringify(toReplace).asString());
		            if (result == null || result.isNull())
		                HostImpl.me.getLogger().error("!!!! Scribus generator returned EMPTY result : "+code);
		        } else {
				    com.planvision.visionr.host.core.scripting.oscript.api.io.File vf = result.getMember("get_file").execute().as(com.planvision.visionr.host.core.scripting.oscript.api.io.File.class);
				    File outf = vf.getFile();
				    ObjectReference resr = result.getMember("_ref").as(ObjectReference.class);
			        Utils.putCachedResult(ckey,outf,resr);
					// ADD TEMP READ 
			        String duuid = (String) resr.getObject().getSimpleValueByCodeUnsafe("uuid");
			        HostImpl.me.addDocumentTempReadAccessSession(null/*current session*/, duuid);
		        }
		        return result;
			}
		 };
		 return Locker.executeInTempFileLock(key,cb);
	}
	
	private ObjectLongHashMap<String> lastUpdateTimes = new ObjectLongHashMap();

	private static boolean inherits(Value vd,String module,String objectdef) {
		if (!JSEngine.isInstance(vd)) return false;
    	DBObjectDef ko = DBObjectDef.g(vd.getMember("SCHEMA").getMember("KEY").asString());
    	return DBModule.g(module).getSchemaByCode(objectdef).allInheritedFromContains(ko);
	}
	
	private final static String _px1 = RuntimeConfig.projectDir+"/work/tmp/zip/";
	private static String resolve(String key,String sfx) {
		if (sfx != null)
			return _px1+key+"/"+sfx;
		else
			return _px1+key;
	}
	//-----------------------------------------------------------------------------------------------------------------
		
	/* returns outdir */
	private File syncTemplate(Value tmpl) throws VException {
		 Value doc = tmpl.getMember("document");
		 final String key =  "rndr-"+doc.getMember("id").asLong(); 
		 long upd = tmpl.hasMember("update_time") ? tmpl.getMember("update_time").as(Date.class).getTime() : 1;
		 long vup = lastUpdateTimes.get(key);
	     File outdir = new File(resolve(key,null));
		 if (vup != upd || !outdir.exists()) {
			 if (doc != null && !doc.isNull()) {
				 // NEEDS REFRESH
				 forceStop(key);
			     HostImpl.me.getLogger().warn("render.js: updating -> service key ["+key+"]\n\t update time ["+upd+"]\n\t template ["+tmpl.getMember("code").asString()+"]\n\t file ["+doc.getMember("toString").execute().asString()+"]");
			     HostImpl.me.getLogger().warn("java.util.extractInTempDirectory, "+key+", "+doc.getMember("toString").execute());
			     Value td = doc.getMember("get_file").execute();
			     com.planvision.visionr.host.core.scripting.oscript.api.io.File file = td.as(com.planvision.visionr.host.core.scripting.oscript.api.io.File.class);
			     
			     File outDirTmp=new File(outdir.getParentFile(),outdir.getName()+".tmp");
			     if (outDirTmp.exists()) 
			    	Utils.deleteDir(outDirTmp);
			     outDirTmp.mkdirs();
			     Utils.unzip(file.getFile(), outDirTmp);		     
				 if (outdir.exists())
					Utils.deleteDir(outdir);
			     outDirTmp.renameTo(outdir);
			     HostImpl.me.getLogger().info("render.js: content extracted in temp "+outdir);
			     lastUpdateTimes.put(key, upd);				 
			 }
		 } else {
			 try {
				Utils.restoreLinks(outdir);
			} catch (IOException e) {
				HostImpl.me.getLogger().error(e);
			}	
		 }
		 return outdir;
	}
	
	@HostAccess.Export 
	public Value getTemplateImageEntryAsFile(Value tmpl,String code) throws VException {
		 Value doc = tmpl.getMember("document");
	     Value td = doc.getMember("get_file").execute();
	     com.planvision.visionr.host.core.scripting.oscript.api.io.File file = td.as(com.planvision.visionr.host.core.scripting.oscript.api.io.File.class);
	     return JSConverter.VR2JS(file.extractZipEntry(code));
	}

	private final static byte[] emptyPNG = new byte[] {(byte)0x89, (byte)0x50, (byte)0x4e, (byte)0x47, (byte)0x0d, (byte)0x0a, (byte)0x1a, (byte)0x0a, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0d, (byte)0x49, (byte)0x48, (byte)0x44, (byte)0x52, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x01, (byte)0x03, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x25, (byte)0xdb, (byte)0x56, (byte)0xca, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03, (byte)0x50, (byte)0x4c, (byte)0x54, (byte)0x45, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0xa7, (byte)0x7a, (byte)0x3d, (byte)0xda, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x74, (byte)0x52, (byte)0x4e, (byte)0x53, (byte)0x00, (byte)0x40, (byte)0xe6, (byte)0xd8, (byte)0x66, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x49, (byte)0x44, (byte)0x41, (byte)0x54, (byte)0x08, (byte)0xd7, (byte)0x63, (byte)0x60, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x02, (byte)0x00, (byte)0x01, (byte)0xe2, (byte)0x21, (byte)0xbc, (byte)0x33, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x49, (byte)0x45, (byte)0x4e, (byte)0x44, (byte)0xae, (byte)0x42, (byte)0x60, (byte)0x82};
	private final static byte[] emptyPDF = """
%PDF-1.0
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/MediaBox[0 0 3 3]>>endobj
xref
0 4
0000000000 65535 f
0000000010 00000 n
0000000053 00000 n
0000000102 00000 n
trailer<</Size 4/Root 1 0 R>>
startxref
149
%EOF""".getBytes(StandardCharsets.UTF_8);
	
	//-----------------------------------------------------------------------------------------------------------------
	private String getCachedResultKey(Value tmpl,Value data) throws VException {
		 long upd = tmpl.hasMember("update_time") ? tmpl.getMember("update_time").as(Date.class).getTime() : 1;
		 Value toReplace = _getTemplateJSON(tmpl,data,null);
		 return HostImpl.me.getSHA256(upd+":"+JSEngine.jsonStringify(toReplace).asString()).replace('/','$')+".txt";
	}	

	private Value convert(String tmpDirKey, String name, String encodedJSON) throws VException {
		String path = resolve(tmpDirKey,"template.sla");
		File f = new File(path);
		String opath = resolve(tmpDirKey,"result.pdf");
		File of = new File(opath);
		if (!f.exists())
			return returnErrorAsDocument(of, name);
		int ntry = 0;
		ProcessInfo pi = null;
		for (; ntry < 20; ntry++) {
			pi = checkProcess(tmpDirKey, true);
			if (pi == null)
				return returnErrorAsDocument(of, name);
			synchronized (pi) {
				try {
					String cmd = "CONVERT:" + opath + "|" + encodedJSON + "\r\n";
					// log.info(cmd);
					pi.clientSocketWriter.write(cmd);
					pi.clientSocketWriter.flush();
					String result = pi.clientSocketReader.readLine();
					if (result != null) {
						if (result.equals("INTERNAL ERROR"))
							return returnErrorAsDocument(of, name);
						if (result.equals("DONE"))
							return returnFileAsFocument(of, name);
						continue;
					}
					HostImpl.me.getLogger().error("ScribusService.convert returned : " + result);
				} catch (Exception ex) {
					HostImpl.me.getLogger().error("Error in ScribusService : " + ex);
					forceStop(tmpDirKey);
				}
			}
		}
		// ERROR ?
		if (pi.process != null) {
			pi.process.destroyForcibly();
			pi.process = null;
		}
		pi.clientSocket = null;
		pi.clientSocketWriter = null;
		pi.clientSocketReader = null;
		return returnErrorAsDocument(of, name);
	}

	
}

