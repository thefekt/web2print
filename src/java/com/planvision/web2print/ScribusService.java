package com.planvision.web2print;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.FileUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHyperlink;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTText;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.carrotsearch.hppcrt.maps.ObjectLongHashMap;
import com.planvision.visionr.core.CorePrefs;
import com.planvision.visionr.core.ParamsHash;
import com.planvision.visionr.core.VException;
import com.planvision.visionr.core.api.FileAndWebPath;
import com.planvision.visionr.core.api.HostImpl;
import com.planvision.visionr.core.api.RuntimeConfig;
import com.planvision.visionr.core.api.VResultSet;
import com.planvision.visionr.core.misc.StringUtils;
import com.planvision.visionr.core.schema.DBConstants;
import com.planvision.visionr.core.schema.DBLang;
import com.planvision.visionr.core.schema.DBModule;
import com.planvision.visionr.core.schema.DBObjectDef;
import com.planvision.visionr.core.vsql.VSQLCompiledCode;
import com.planvision.visionr.host.JavaHost;
import com.planvision.visionr.host.core.context.VRSessionContext;
import com.planvision.visionr.host.core.scripting.api.Common;
import com.planvision.visionr.host.core.scripting.api.Require;
import com.planvision.visionr.host.core.scripting.api.VSC;
import com.planvision.visionr.host.core.scripting.core.JSEngine;
import com.planvision.visionr.host.core.scripting.core.api.export.Excel;
import com.planvision.visionr.host.core.scripting.oscript.api.io.TmpFile;
import com.planvision.visionr.host.core.scripting.oscript.api.reports.BarCode;
import com.planvision.visionr.host.impl.schema.DBObjectDefImpl;
import com.planvision.visionr.host.impl.schema.ObjectReference;
import com.planvision.visionr.host.master.JSConverter;
import com.planvision.visionr.host.server.PDFEmbeddedLocations;
import com.planvision.visionr.host.server.PDFEmbeddedLocations.Location;
import com.planvision.web2print.Locker.Callback;
import com.planvision.web2print.SLAXML.ContentHandler;
import com.planvision.web2print.SLAXML.XMLHandler;

//Servicing TMS like request for document preview with OpenSeaDragon
public class ScribusService {
	
	private static String _executable = null;
	private static final int inactiveDurationMS = (int)(CorePrefs.getDoublePref("web2print.scribus.inactive.expire.minutes",2.0)*60000.0);
	private static final class ProcessInfo {
		public Process process = null;
		public OutputStreamWriter clientSocketWriter;
		public BufferedReader clientSocketReader;
		public Socket clientSocket;
		public int port;
		private long lastExecution = System.currentTimeMillis();
		public void ping() {
			lastExecution = System.currentTimeMillis();
		}
		public boolean expired(long now) {
			if (now-lastExecution > inactiveDurationMS)
				return true;
			return false;
		}
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
				_executable += "/scribus.exe";
			} else if (RuntimeConfig.isMac) {
				_executable += "/scribus";
			} else {
				_executable += "/scribus";
			}
		}
		return _executable;
	}

	// -----------------------------------------------------------------------------
	@HostAccess.Export
	public void forceStop(String tmpDirKey) {
		for (DBLang l : HostImpl.me.getActiveLanguages()) {
			ProcessInfo pi = checkProcess(tmpDirKey, l.getCode(), false);
			if (pi == null)
				continue;
			if (pi.process != null) {
				pi.process.destroyForcibly();
				pi.process = null;
			}
			pi.clientSocket = null;
			pi.clientSocketWriter = null;
			pi.clientSocketReader = null;
			pi.newPort();
		}
	}

	private static Value returnFileAsFocument(File file, String name, Value regions) throws VException {
		String extension = file.getName().substring(file.getName().lastIndexOf('.') + 1);
		FileAndWebPath uf = JavaHost.me.createTmpFile(extension);
		file.renameTo(new File(uf.absolutePath));
		String documentCode = StringUtils.randomHex16TS() + "." + extension;
		Value doc = Require.root.require("server/runtime/documents").getMember("moveToUpload").execute(uf.webpath,
				documentCode, name, extension);
		Value res = JSEngine.newEmptyObject();
		res.putMember("doc", doc);
		res.putMember("regs", regions);
		return res;
	}

	private static Value returnErrorAsDocument(File ofile, String name) throws VException {
		try {
			FileUtils.copyFile(new File(RuntimeConfig.projectDir + "/src/bin/python/internal-error.pdf"), ofile);
		} catch (IOException e) {
			HostImpl.me.getLogger().error(e);
		}
		return returnFileAsFocument(ofile, name, JSEngine.NULL);
	}

	private static Collection<Map<String, Object>> transformExcel(File f) {
		if (!f.exists())
			return null;
		Excel excl = new Excel();
		Map<String, Object> cdata = excl.readExcelData(f, true);
		Collection<Map<String, Object>> cols = (Collection) cdata.get("columns");
		String cnames[] = new String[cols.size()];
		int pos = 0;
		for (Map<String, Object> col : cols) {
			cnames[pos++] = (String) col.get("name");
		}
		Vector<Map<String, Object>> res = new Vector();
		Collection<Collection<Object>> data = (Collection) cdata.get("data");
		for (Collection<Object> row : data) {
			int ccol = 0;
			HashMap<String, Object> rh = new HashMap();
			for (Object v : row) {
				rh.put(cnames[ccol], v);
				ccol++;
			}
			res.add(rh);
		}
		return res;
	}

	@HostAccess.Export
	public Value getAvailableContents(String tmpDirKey) throws VException {

		// contents definition as table
		String contentsPath = resolve(tmpDirKey, "contents.xlsx");
		Collection<Map<String, Object>> contentsData = transformExcel(new File(contentsPath));
		// ----------------------------------------------------------------------------------
		// calculate code 2 page map, throw error if content (tag) in multiple pages
		Map<String, Integer> code2page = new HashMap();
		Map<String, Map<String, String>> code2InitialByLang = new HashMap(); // all values by lang !! type still not
																				// known (i18n or not) !
		Map<String, Double> code2ImageProportion = new HashMap();
		Value dynamicColors = JSEngine.newEmptyObject(); // by code
		
		for (DBLang l : HostImpl.me.getActiveLanguages()) {
			// template file (by lang or DEF)
			String path = resolve(tmpDirKey, "template." + l.getCode() + ".sla");
			File f = new File(path);
			if (f.exists()) {
				SLAXML.walkAll(f, new XMLHandler() {

					@Override
					public void handleNode(Node node) throws VException {
						switch (node.getNodeName()) {
							case "COLOR":
								String name = node.getAttributes().getNamedItem("NAME").getNodeValue();
								if (name.startsWith("(") && name.endsWith(")") && node.getAttributes().getNamedItem("SPACE").getNodeValue().equals("CMYK")) {
									double C = Double.parseDouble( node.getAttributes().getNamedItem("C").getNodeValue() );
									double M = Double.parseDouble( node.getAttributes().getNamedItem("M").getNodeValue() );
									double Y = Double.parseDouble( node.getAttributes().getNamedItem("Y").getNodeValue() );
									double K = Double.parseDouble( node.getAttributes().getNamedItem("K").getNodeValue() );
									Value no = JSEngine.newEmptyObject();
									
									String cmyk = "("+C+" ,"+M+" ,"+Y+" ,"+K+")";
									String code = "CMYK"+cmyk;
									String rgb = cmykToRgb(C,M,Y,K);
									no.putMember("CMYK", cmyk);
									no.putMember("RGB", rgb);
									no.putMember("CODE", code);
									dynamicColors.putMember(name.substring(1,name.length()-1), no);
								}
								//<COLOR NAME="(COLOR)" SPACE="CMYK" C="4.31372549019608" M="2.74509803921569" Y="88.2352941176471" K="0"/>
						}
					}
				
				});
				
				SLAXML.walkContents(f, new ContentHandler() {
					@Override
					public void handleContent(String code, String text, int page, Node node) throws VException {
						Integer op = code2page.get(code);
						if (op == null)
							code2page.put(code, page);
						else if (op != page)
							throw new VException("Incompatible content with CODE " + code
									+ " : page differs in template versions (language) : " + op + " <> " + page);
						Map<String, String> m = code2InitialByLang.get(code);
						if (m == null) {
							m = new HashMap();
							code2InitialByLang.put(code, m);
						}
						if (text != null && !_isPlaceholderString(text)) {
							m.put(l.getCode(), text);
						}

						Node nw = node.getAttributes().getNamedItem("WIDTH");
						if (nw == null)
							return;
						Node nh = node.getAttributes().getNamedItem("HEIGHT");
						if (nh == null)
							return;
						double prop = (Double.parseDouble(nw.getNodeValue())) / (Double.parseDouble(nh.getNodeValue()));
						code2ImageProportion.put(code, prop);
					}
				});
			}
		}
		// ----------------------------------------------------------------------------------
		HashSet<String> tableSet = new HashSet(); // used images
		Vector<Map<String, Object>> entries = new Vector(); // contents array
		Map<String, Map<String, String>> categoryNamesByCode = new HashMap(); // category[code] > i18n name
		HashSet<String> embeddedSet = new HashSet(); // embedded documents
		// parse contents table data (excel)
		for (Map<String, Object> r : contentsData) {
			String code = (String) r.get("CODE");
			String cat = (String) r.get("CATEGORY");
			String type = (String) r.get("TYPE");
			if (code != null && !code.isBlank()) {
				int xc = code.lastIndexOf('/');
				boolean isEmbdVar = xc > 0;
				if (isEmbdVar) {
					String pfx = code.substring(0, xc);
					if (pfx.endsWith(".docx") || pfx.endsWith(".xlsx"))
						if (embeddedSet.add(pfx)) {
							for (DBLang l : HostImpl.me.getActiveLanguages()) {
								String path = resolve(tmpDirKey, pfx.substring(0, pfx.length() - 4) + l.getCode()
										+ pfx.substring(pfx.length() - 5));
								File f = new File(path);
								if (f.exists() && f.canRead()) {
									docxExtractInitialValues(f, l.getCode(), code2InitialByLang, pfx);
								}
							}
						}
				}

				HashMap<String, Object> va = new HashMap();
				// CONTENT
				if (type == null || type.isBlank())
					type = "STRING";
				boolean isqr = false;
				Map<String, String> ival = code2InitialByLang.get(code);
				switch (type) {
				case "COLOR": {
					String path = resolve(tmpDirKey, code + ".xlsx");
					File tf = new File(path);
					Value nd = JSEngine.newEmptyArray();
					Value dc = dynamicColors.getMember(code);
					if (dc != null && !dc.isNull()) 
						nd.setArrayElement(nd.getArraySize(),dc);
					if (tf.canRead()) {
						Value ds = JSConverter.VR2JS((new Excel()).readExcelData(tf, true));
						Value cols = ds.getMember("columns");
						Value data = ds.getMember("data");
						for (int j=0;j<data.getArraySize();j++) {
							Value dj = data.getArrayElement(j);
							Value nt = JSEngine.newEmptyObject();
							for (int k=0;k<dj.getArraySize();k++) 
								nt.putMember(cols.getArrayElement(k).getMember("name").asString(), dj.getArrayElement(k));
							nd.setArrayElement(nd.getArraySize(),nt);
						}
					}
					va.put("data",nd);
					va.put("type", "indexed_color");
					break;
				}
				case "QRCODE":
				case "QR": /* ALIAS */
					isqr = true;
				case "DOCUMENT":
				case "IMAGE":
					for (DBLang lng : HostImpl.me.getActiveLanguages()) {
						String _path = resolve(tmpDirKey, code + "." + lng.getCode());
						String path = _path + ".png";
						File tf = new File(path);
						if (!tf.exists())
							tf = new File(path = (_path + ".jpg"));
						if (!tf.exists())
							tf = new File(path = (_path + ".jpeg"));
						if (!tf.exists())
							tf = new File(path = (_path + ".pdf"));
						if (tf.exists()) {
							Collection<String> files = (Collection<String>) va.get("files");
							if (files == null)
								va.put("files", files = new Vector());
							files.add(path);
						}
					}
					Double prop = code2ImageProportion.get(code);
					if (prop != null && prop > 0) {
						va.put("proportion", prop);
						va.put("type", isqr ? "qrcode" : "image");
					} else
						continue;
					break;
				case "DATE":
					if (ival != null) {
						for (DBLang lng : HostImpl.me.getActiveLanguages()) {
							String vv = ival.get(lng.getCode());
							if (vv == null)
								continue;
							if (vv.isBlank())
								continue;
							org.graalvm.polyglot.Value jsv = JavaHost.me.VR2JS(Common.convertValueByCoreFormat(
									"default_input_format_date", JSConverter.JS2VR(vv), lng.id));
							if (jsv.isDate()) {
								va.put("initial", jsv.asDate());
								break;
							}
						}
					}
					va.put("type", "date");
					break;
				case "DATETIME":
					if (ival != null) {
						for (DBLang lng : HostImpl.me.getActiveLanguages()) {
							String vv = ival.get(lng.getCode());
							if (vv == null)
								continue;
							if (vv.isBlank())
								continue;
							org.graalvm.polyglot.Value jsv = JavaHost.me.VR2JS(Common.convertValueByCoreFormat(
									"default_output_format_datetime_hour_minutes", JSConverter.JS2VR(vv), lng.id));
							if (jsv.isDate()) {
								va.put("initial", jsv.asDate());
								break;
							}
						}
					}
					va.put("type", "datetime");
					break;
				case "TIME":
					if (ival != null) {
						for (DBLang lng : HostImpl.me.getActiveLanguages()) {
							String vv = ival.get(lng.getCode());
							if (vv == null)
								continue;
							if (vv.isBlank())
								continue;
							org.graalvm.polyglot.Value jsv = JavaHost.me.VR2JS(Common.convertValueByCoreFormat(
									"default_output_format_hours_minutes", JSConverter.JS2VR(vv), lng.id));
							if (jsv.isDate()) {
								va.put("initial", jsv.asDate());
								break;
							}
						}
					}
					va.put("type", "time");
					break;
				case "INTEGER":
					if (ival != null) {
						for (DBLang lng : HostImpl.me.getActiveLanguages()) {
							String vv = ival.get(lng.getCode());
							if (vv == null)
								continue;
							if (vv.isBlank())
								continue;
							org.graalvm.polyglot.Value jsv = JavaHost.me.VR2JS(Common.convertValueByCoreFormat(
									"output_format_integer_separator", JSConverter.JS2VR(vv), lng.id));
							if (jsv.isNumber()) {
								va.put("initial", jsv.as(Long.class));
								break;
							}
						}
					}
					va.put("type", "integer");
					break;
				case "NUMBER":
				case "FLOAT": /* ALIAS */
				case "DOUBLE": /* ALIAS */
					if (ival != null) {
						for (DBLang lng : HostImpl.me.getActiveLanguages()) {
							String vv = ival.get(lng.getCode());
							if (vv == null)
								continue;
							if (vv.isBlank())
								continue;
							org.graalvm.polyglot.Value jsv = JavaHost.me.VR2JS(Common.convertValueByCoreFormat(
									"default_output_format_double", JSConverter.JS2VR(vv), lng.id));
							if (jsv.isNumber()) {
								va.put("initial", jsv.asDouble());
								break;
							}
						}
					}
					va.put("type", "double");
					break;
				case "STRING":
				case "VARCHAR": /* ALIAS */
					va.put("type", "varchar");
					if (ival != null)
						va.put("initial", ival);
					break;
				case "TEXT":
					va.put("type", "text");
					if (ival != null)
						va.put("initial", ival);
					break;
				case "TABLE":
					if (tableSet.add(code)) {
						StringBuilder sb = new StringBuilder("{");
						for (DBLang lng : HostImpl.me.getActiveLanguages()) {
							File fe = new File(resolve(tmpDirKey, code + "." + lng.getCode() + ".xlsx"));
							if (!fe.exists() || !fe.canRead())
								fe = new File(resolve(tmpDirKey, code + "." + lng.getCode() + ".xls"));
							if (fe.exists() && fe.canRead()) {
								org.graalvm.polyglot.Value res = JSConverter
										.VR2JS((new Excel()).readExcelData(fe, true));
								if (sb.length() > 1)
									sb.append(",");
								sb.append("\"");
								sb.append(lng.getCode());
								sb.append("\":{\"columns\":[");
								org.graalvm.polyglot.Value cols = res.getMember("columns");
								for (int ci = 0; ci < cols.getArraySize(); ci++) {
									if (ci != 0)
										sb.append(",");
									org.graalvm.polyglot.Value ccol = cols.getArrayElement(ci);
									sb.append("{\"name\":");
									String name = ccol.getMember("name").asString();
									StringUtils.stringToJSONToStringBuilder(name, sb);
									for (String ks : ccol.getMemberKeys())
										if (!ks.equals("name")) {
											sb.append(",");
											StringUtils.stringToJSONToStringBuilder(ks, sb);
											sb.append(":");
											sb.append(JSEngine.jsonStringify(ccol.getMember(ks)));
										}
									sb.append("}");
								}
								sb.append("],\"data\":[");
								org.graalvm.polyglot.Value dat = res.getMember("data");
								int nrows = (int) dat.getArraySize();
								int ncols = 0;
								for (int ci = 0; ci < nrows; ci++) {
									if (ci != 0)
										sb.append(",");
									org.graalvm.polyglot.Value crow = dat.getArrayElement(ci);
									sb.append("[");
									int ccols = (int) crow.getArraySize();
									if (ccols > ncols)
										ncols = ccols;
									for (int cj = 0; cj < ccols; cj++) {
										if (cj != 0)
											sb.append(",");
										org.graalvm.polyglot.Value ce = crow.getArrayElement(cj);
										if (ce.isString()) {
											String name = ce.asString();
											StringUtils.stringToJSONToStringBuilder(name, sb);
										} else if (ce.isDate()) {
											sb.append("{\"_TYPE_\":\"Date\",\"ltime\":");
											sb.append(ce.as(Date.class).getTime());
											sb.append("}");
										} else {
											sb.append(JSEngine.jsonStringify(ce));
										}
									}
									sb.append("]");
								}
								sb.append("]}");
								va.put("columns", ncols); // COUNT
								va.put("rows", nrows); // COUNT
								va.put("type", "table");
							}
						}
						sb.append("}");
						va.put("data",sb.toString());
					}
					break;
				default:
					continue;
				}
				HashMap<String, String> name = new HashMap();
				for (DBLang l : HostImpl.me.getActiveLanguages()) {
					String vl = (String) r.get("NAME." + l.getCode());
					if (vl != null && !vl.isBlank())
						name.put(l.getCode(), vl);
				}
				Integer page = code2page.get(code);
				if (page == null)
					page = 0;
				va.put("code", code);
				va.put("name", name);
				va.put("page", page);
				va.put("category", cat);
				entries.add(va);
			} else if (cat != null && !cat.isBlank()) {
				// CATEGORY
				Map<String, String> c = categoryNamesByCode.get(cat);
				if (c == null) {
					c = new HashMap();
					categoryNamesByCode.put(cat, c);
				}
				for (DBLang l : HostImpl.me.getActiveLanguages()) {
					String vl = (String) r.get("NAME." + l.getCode());
					if (vl != null && !vl.isBlank())
						c.put(l.getCode(), vl);
				}
			}
		}
		Value res = JSEngine.newEmptyObject();
		res.putMember("entries", JSConverter.VR2JS(entries));
		res.putMember("categories", JSConverter.VR2JS(categoryNamesByCode));
		return res;

	}

	private static ProcessInfo checkProcess(String key, String lang, boolean doStart) {
		ProcessInfo pi;
		String keyl = key + "." + lang;
		synchronized (processes) {
			if (cleanupThread == null)
				cleanupThread = Thread.ofPlatform().start(cleanupTask);
			pi = processes.get(keyl);
			if (pi == null) {
				pi = new ProcessInfo();
				processes.put(keyl, pi);
			}
			pi.ping();
		}
		if (pi.process != null && !pi.process.isAlive())
			pi.process = null;
		if (pi.process == null) {
			if (!doStart)
				return null;
			ProcessBuilder pb = new ProcessBuilder();
			// --no-gui -ns -g -py scrbserv2.py -pa 9988 -- good1.sla
			String pySrc = RuntimeConfig.projectDir + "/src/bin/python/scribserv.py";

			HostImpl.me.getLogger()
					.warn(">>>> \"" + getExecutable() + "\" \"" + resolve(key, "template." + lang + ".sla")
							+ "\" --no-gui -ns -g -py \"" + pySrc + "\" " + pi.port);

			// xvfb-run REQUIRED ON LINUX/UNIX PLATFORMS
			if (RuntimeConfig.isWin || RuntimeConfig.isMac) {
				pb.command(getExecutable(), resolve(key, "template." + lang + ".sla"), "--no-gui", "-ns", "-g", "-py",
						pySrc, "" + pi.port);
			} else {
				pb.environment().put("DISPLAY", ":0");
				pb.command(getExecutable(), resolve(key, "template." + lang + ".sla"), "--no-gui", "-ns", "-g", "-py",
						pySrc, "" + pi.port);
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
						return checkProcess(key, lang, doStart);
					if (pi.clientSocket == null || pi.clientSocket.isClosed()) {
						pi.clientSocket = new Socket("127.0.0.1", pi.port);
						pi.clientSocketWriter = new OutputStreamWriter(pi.clientSocket.getOutputStream(),
								StandardCharsets.UTF_8);
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
		Value dirr = null;
		boolean isi = JSEngine.isInstance(tmpl);
		if (isi) {
			VSQLCompiledCode nparent = HostImpl.me.compileCachedVSQL(DBModule.g("documents").getSchemaByCode("folder"),
					"SELECT id,objectdef WHERE path = :PATH");
			ParamsHash params = new ParamsHash();
			String dirp = "/web2print/" + tmpl.getMember("uuid").asString() + "/defaults";
			params.put("PATH", dirp);
			VResultSet rs = HostImpl.me.getConnection().executeQuery(nparent, params,
					VRSessionContext.accessContextAdmin);
			try {
				if (!rs.next())
					throw new VException("Unable to find defaults directory : " + dirp);
				dirr = ObjectReference.make(rs.getLong(1), rs.getInt(2)).getJSObj();
			} finally {
				rs.close();
			}
		}
		final Value dirrf = dirr;

		Value ext = tmpl.getMember("document").getMember("extension");
		if (ext == null || ext.isNull() || !ext.getMember("code").isString()
				|| !ext.getMember("code").asString().equalsIgnoreCase("ZIP")) {
			Value te = JSEngine.newEmptyObject();
			te.putMember("err", "ZIP file expected!");
			return te;
		}
		final String key = "rndr-" + tmpl.getMember("document").getMember("id").asLong();
		Callback cb = new Callback() {
			@Override
			public Value execute() throws VException {
				/* File outdir = */syncTemplate(tmpl);
				Value cdata = getAvailableContents(key);
				Value contents = cdata.getMember("entries");
				Value cats = cdata.getMember("categories");
				Value userRoleEveryone = ObjectReference
						.make(DBConstants.USER_ROLE_EVERYONE, DBConstants.CORE_USER_ROLE_OBJECTDEF).getJSObj(); // TODO
																												// DO
																												// NOT
																												// ADD
																												// EVERYONE,
																												// ADD
																												// ACCESS
																												// DISABLED
																												// PROPERTIES
																												// category
				if (isi) {
					Value x = tmpl.getMember("contents");
					if (!x.isNull()) {
						int sz = (int) x.getArraySize();
						for (int i = 0; i < sz; i++) {
							Value o = x.getArrayElement(i);
							o.getMember("delete").execute();
							o.getMember("commit").execute();
						}
					}
					x = tmpl.getMember("categories_content");
					if (!x.isNull()) {
						int sz = (int) x.getArraySize();
						for (int i = 0; i < sz; i++) {
							Value o = x.getArrayElement(i);
							o.getMember("delete").execute();
							o.getMember("commit").execute();
						}
					}
					Value odcat = ((DBObjectDefImpl) DBModule.g("web2print").getSchemaByCode("category_content").impl())
							.getJSProxy();
					for (String catcode : cats.getMemberKeys().toArray(new String[cats.getMemberKeys().size()])) {
						Value nameByLang = cats.getMember(catcode);
						Value cat = odcat.newInstance();
						cat.putMember("code", catcode);
						cat.putMember("print_template", tmpl);
						for (String lc : nameByLang.getMemberKeys()) {
							DBLang l = DBLang.g(lc);
							if (l == null)
								continue;
							cat.getMember("setI18n").execute("name", l.getCode(), nameByLang.getMember(lc).asString());
						}
						cats.putMember(catcode, cat);
						cat.putMember("access_read", userRoleEveryone);
					}
				}
				Value arr = JSEngine.newEmptyArray();
				int sz = (int) contents.getArraySize();
				for (var i = 0; i < sz; i++) {
					Value e = contents.getArrayElement(i);
					String type = e.getMember("type").asString();
					String code = e.getMember("code").asString();
					if (isi && code.equals("$TEMPLATE$")) {
						_genCommonName(tmpl, e);
						continue;
					}
					String cat = e.hasMember("category") ? e.getMember("category").asString() : null;
					Value p = e.getMember("page");
					switch (type) {
					case "integer":
					case "double":
					case "datetime":
					case "time":
					case "date":
					case "text":
					case "varchar": {
						Value c = _genCommonInput(code, type, p, tmpl, e);
						if (cat != null)
							c.putMember("category", cats.getMember(cat));
						Value cc = c.getMember("commit");
						if (cc != null)
							cc.execute();
						arr.setArrayElement(arr.getArraySize(), c);
						break;
					}
					case "indexed_color": {
						Value c;
						Value data = e.getMember("data");
						if (isi) {
							Value cpx = ((DBObjectDefImpl)DBModule.g("web2print").getSchemaByCode("color").impl()).getJSProxy();
							c = ((DBObjectDefImpl) DBModule.g("web2print").getSchemaByCode("indexed_color_content")
									.impl()).getJSProxy().newInstance();
							c.putMember("template", tmpl);
							_genCommonName(c, e);
							if (data != null && !data.isNull()) 
							{
								for (int j=0;j<data.getArraySize();j++) {
									Value d = data.getArrayElement(j);
									Value a = d.getMember("CODE");
									if (a == null || a.isNull())
										continue;
									String ccode = a.asString();
									a = d.getMember("RGB");
									if (a == null || a.isNull())
										continue;
									String valrgb = "rgb"+a.asString();
									a = d.getMember("CMYK");
									if (a == null || a.isNull())
										continue;
									String valcmyk= "cmyk"+a.asString();
									Value col = cpx.getMember("byCode").execute(ccode);
									if (col == null || col.isNull()) {
										col = cpx.newInstance();
										col.putMember("code", ccode);
									}
									col.putMember("value_rgb", valrgb);
									col.putMember("value_cmyk", valcmyk);
									for (DBLang l : HostImpl.me.getActiveLanguages()) {
										Value v = d.getMember("NAME."+l.getCode());
										if (v != null && !v.isNull()) 
											col.getMember("setI18n").execute("name", l.getCode(),v.asString());
									}
									col.putMember("access_read", userRoleEveryone);
									c.getMember("push").execute("available_colors",col);
									if (j == 0)
										c.putMember("initial_value", col); // TODO
								}
							}
						} else {
							c = JSEngine.newEmptyObject();
							c.putMember("type", type);
							c.putMember("data", data);
						}
						if (cat != null)
							c.putMember("category", cats.getMember(cat));
						c.putMember("code", code);
						c.putMember("dest_page", p);
						

						
						
						
						Value cc = c.getMember("commit");
						if (cc != null)
							cc.execute();
						arr.setArrayElement(arr.getArraySize(), c);
						break;
					}
					case "image": {
						Value c;
						if (isi) {
							c = ((DBObjectDefImpl) DBModule.g("web2print").getSchemaByCode("image_content").impl())
									.getJSProxy().newInstance();
							c.putMember("template", tmpl);
							_genCommonName(c, e);
						} else {
							c = JSEngine.newEmptyObject();
							c.putMember("type", type);
						}
						if (cat != null)
							c.putMember("category", cats.getMember(cat));
						c.putMember("code", code);
						c.putMember("dest_page", p);
						c.putMember("proportion", e.getMember("proportion"));

						Value f = e.getMember("files");
						if (f != null && !f.isNull() && isi) {
							for (int r = 0; r < f.getArraySize(); r++) {
								String fpath = f.getArrayElement(r).asString();
								int x1 = fpath.lastIndexOf('.');
								int x2 = fpath.lastIndexOf('.', x1 - 1);
								String lang = fpath.substring(x2 + 1, x1);
								String ext = fpath.substring(x1 + 1);
								TmpFile a = new TmpFile(ext);
								try {
									try {
										FileUtils.copyFile(new File(fpath), a.getFile());
										String docc = code + "." + lang + "." + ext;
										Value doc = VSC.callVSC("doc.misc.uploadInDir",
												new Value[] { dirrf, JSConverter.VR2JS(docc), JSEngine.UNDEFINED,
														JSConverter.VR2JS(ext), JSEngine.UNDEFINED/* description */,
														JSConverter.VR2JS(a) });
										doc.putMember("code", docc); // force code exact (no prefix TEST-.. )
										// doc.putMember("parent",dirrf);
										doc.getMember("commit").execute();

										// c.putMember("initial_value", doc); NO i18n value, because images by lang
										// (i18n)
									} catch (VException ex) {
										HostImpl.me.getLogger()
												.error("Error uploading default image as document " + ex);
									}
								} catch (IOException e1) {
									HostImpl.me.getLogger().error(e1);
								}
							}
						}
						Value cc = c.getMember("commit");
						if (cc != null)
							cc.execute();
						arr.setArrayElement(arr.getArraySize(), c);
						break;
					}
					case "table": {
						Value c;
						if (isi) {
							c = ((DBObjectDefImpl) DBModule.g("web2print").getSchemaByCode("table_content").impl())
									.getJSProxy().newInstance();
							c.putMember("template", tmpl);
							_genCommonName(c, e);

							// TABLE DATA, JSON BY LANGUAGE
							Value dta = e.getMember("data");
							if (dta != null && dta.isString()) {
								dta = JSEngine.jsonParse(dta.asString());
								for (DBLang l : HostImpl.me.getActiveLanguages()) {
									Value tda = dta.getMember(l.getCode());
									if (!tda.isNull())
										c.getMember("setI18n").execute("table_data", l.getCode(),
												JSEngine.jsonStringify(tda));
								}
							}
						} else {
							c = JSEngine.newEmptyObject();
							c.putMember("type", type);
							c.putMember("table_data", e.getMember("data"));
						}
						if (cat != null)
							c.putMember("category", cats.getMember(cat));
						c.putMember("code", code);
						c.putMember("dest_page", p);
						c.putMember("column_count", e.getMember("columns"));
						c.putMember("row_count", e.getMember("rows"));

						Value cc = c.getMember("commit");
						if (cc != null)
							cc.execute();
						arr.setArrayElement(arr.getArraySize(), c);
						break;
					}
					case "qrcode": {
						Value c;
						if (isi) {
							c = ((DBObjectDefImpl) DBModule.g("web2print").getSchemaByCode("qrcode_content").impl())
									.getJSProxy().newInstance();
							c.putMember("template", tmpl);
							_genCommonName(c, e);
						} else {
							c = JSEngine.newEmptyObject();
							c.putMember("type", type);
						}
						if (cat != null)
							c.putMember("category", cats.getMember(cat));
						c.putMember("code", code);
						c.putMember("dest_page", p);
						c.putMember("initial_value", CorePrefs.getServerHostExternal());
						Value cc = c.getMember("commit");
						if (cc != null)
							cc.execute();
						arr.setArrayElement(arr.getArraySize(), c);
						break;
					}
					default:
						HostImpl.me.getLogger().warn("!!!TODO!!! initTemplateContents for type " + type);
					}
				}
				tmpl.putMember("contents", arr);
				Value cc = tmpl.getMember("commit");
				if (cc != null)
					cc.execute();
				return tmpl;
			}
		};
		return Locker.executeInTempFileLock(key + ".INIT", cb);
	}

	@HostAccess.Export
	public Value getTemplateJSON(Value tmpl, String lang, Value data) throws VException {
		if (lang == null)
			lang = DBLang.g(VRSessionContext.getCurrentLang()).getCode();
		return _getTemplateJSON(tmpl, data, lang, null, null, null);
	}

	private void _genCommonName(Value c, Value data) {
		Value name = data.getMember("name");
		if (name != null && !name.isNull()) {
			for (String k : name.getMemberKeys()) {
				DBLang l = DBLang.g(k);
				if (l != null)
					c.getMember("setI18n").execute("name", l.getCode(), name.getMember(k));
			}
		}
	}

	private Value _genCommonInput(String code, String type, Value page, Value tmpl, Value data) {
		Value c;
		boolean isInst = JSEngine.isInstance(tmpl);
		if (isInst) {
			c = ((DBObjectDefImpl) DBModule.g("web2print").getSchemaByCode(type + "_content").impl()).getJSProxy()
					.newInstance();
			c.putMember("template", tmpl);
		} else {
			c = JSEngine.newEmptyObject();
			c.putMember("type", type);
		}
		c.putMember("code", code);
		c.putMember("dest_page", page);

		if (isInst)
			_genCommonName(c, data);

		Value initial = data.getMember("initial");
		if (!isInst) {
			if (initial != null && !initial.isNull())
				c.putMember("initial_value", initial);
		} else {
			switch (type) {
			case "double": {
				Object ij = getInitialNotI18n(initial);
				if (!(ij instanceof Number))
					ij = 1;
				c.putMember("initial_value", ij);
				break;
			}
			case "integer": {
				Object ij = getInitialNotI18n(initial);
				if (!(ij instanceof Number))
					ij = 1;
				c.putMember("initial_value", 1);
				break;
			}
			case "time":
			case "date":
			case "datetime": {
				Object ij = getInitialNotI18n(initial);
				if (ij instanceof Date)
					c.putMember("initial_value", ij);
				break;
			}
			case "varchar": /* i18n */
			case "text":
				if (initial != null && !initial.isNull()) {
					for (DBLang l : HostImpl.me.getActiveLanguages()) {
						Object v = initial.getMember(l.getCode());
						if (v != null)
							c.getMember("setI18n").execute("initial_value", l.getCode(), v);
					}
				}
			}
		}
		Value cc = c.getMember("commit");
		if (cc != null)
			cc.execute();
		return c;
	}

	private static Object getInitialNotI18n(Value a) {
		if (a == null || a.isNull())
			return null;
		if (a.isString()) {
			String as = a.asString();
			if (as.isBlank())
				return null;
			return as;
		}
		if (a.isNumber())
			return a.as(Number.class);
		if (a.isDate())
			return a.as(Date.class);
		return a;

	}

	private String defaultLangStr = DBLang.g(RuntimeConfig.defaultLang).getCode();

	private Value getI18nOrDef(Value e, String pro, String lang) {
		if (JSEngine.isInstance(e))
			return e.getMember("getI18nOrDef").execute(pro, lang);
		else {
			e = e.getMember(pro);
			if (e == null || e.isNull())
				return e;
			Value v = e.getMember(lang);
			if (v == null || v.isNull())
				v = e.getMember(defaultLangStr);
			return v;
		}
	}

	private Value _getTemplateJSON(Value tmpl, Value data, String lang, File tmpDir, Value regions, Value emds)
			throws VException {
		boolean doNotRender = tmpDir == null;
		Value toReplace = JSEngine.newEmptyObject();
		Value contents = tmpl.getMember("contents");
		int sz = contents.isNull() ? 0 : (int) contents.getArraySize();
		DBLang lng = DBLang.g(lang);

		HashSet<String> embeddedSet = new HashSet();
		HashMap<String, String> code2Embedded = new HashMap();
		HashMap<String, String> code2EmbeddedVal = new HashMap();

		for (int i = 0; i < sz; i++) {
			Value e = contents.getArrayElement(i);
			String code = e.getMember("code").asString();

			int xc = code.lastIndexOf('/');
			boolean isEmbdVar = xc > 0;

			String type = e.hasMember("type") ? e.getMember("type").asString() : null;
			if ("varchar".equals(type) || inherits(e, "web2print", "varchar_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = getI18nOrDef(e, "initial_value", lang);
				toReplace.putMember(code, vd);
			} else if ("text".equals(type) || inherits(e, "web2print", "text_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = getI18nOrDef(e, "initial_value", lang);
				toReplace.putMember(code, vd);
			} else if ("date".equals(type) || inherits(e, "web2print", "date_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = e.getMember("initial_value");
				if (vd == null || vd.isNull())
					toReplace.putMember(code, "");
				else
					toReplace.putMember(code, Common.convertValueByCoreFormat("default_output_format_date",
							JSConverter.JS2VR(vd), lng.id));
			} else if ("datetime".equals(type) || inherits(e, "web2print", "datetime_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = e.getMember("initial_value");
				if (vd == null || vd.isNull())
					toReplace.putMember(code, "");
				else
					toReplace.putMember(code, Common.convertValueByCoreFormat(
							"default_output_format_datetime_hour_minutes", JSConverter.JS2VR(vd), lng.id));
			} else if ("time".equals(type) || inherits(e, "web2print", "time_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = e.getMember("initial_value");
				if (vd == null || vd.isNull())
					toReplace.putMember(code, "");
				else
					toReplace.putMember(code, Common.convertValueByCoreFormat("default_output_format_hours_minutes",
							JSConverter.JS2VR(vd), lng.id));
			} else if ("integer".equals(type) || inherits(e, "web2print", "integer_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = e.getMember("initial_value");
				if (vd == null || vd.isNull())
					toReplace.putMember(code, "");
				else
					toReplace.putMember(code, Common.convertValueByCoreFormat("output_format_integer_separator",
							JSConverter.JS2VR(vd), lng.id));
			} else if ("double".equals(type) || inherits(e, "web2print", "double_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = e.getMember("initial_value");
				if (vd == null || vd.isNull())
					toReplace.putMember(code, "");
				else
					toReplace.putMember(code, Common.convertValueByCoreFormat("default_output_format_double",
							JSConverter.JS2VR(vd), lng.id));
			} else if ("image".equals(type) || inherits(e, "web2print", "image_content")) {
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
				if (inherits(vd, "documents", "file")) {
					DBObjectDef ko = DBObjectDef.g(vd.getMember("SCHEMA").getMember("KEY").asString());
					if (DBModule.g("documents").getSchemaByCode("file").allInheritedFromContains(ko)) {
						// instanceof od documents file
						boolean isDisabled = disabled != null && disabled.asBoolean();
						// KEY for image tag
						if (tmpDir == null) {
							// during cache key calc, not during render (do not add hashes to the real
							// replace set)
							long k1 = 0;
							Value t = vd.getMember("update_time");
							if (t.isNull())
								t = vd.getMember("insert_time");
							if (t.isDate())
								k1 = t.as(Date.class).getTime();
							String key = HostImpl.me.getSHA256(vd.getMember("id").asLong() + '_' + k1 + '_'
									+ (isDisabled ? '1' : '0') + '_' + JSEngine.jsonStringify(region).asString());
							toReplace.putMember(code, key);
						}
						// ---------------------------
						if (!doNotRender) {
							String uuid = vd.getMember("uuid").asString();
							File file = null;
							try {
								file = HostImpl.me.resolveResourceURL("/documents/" + uuid + ".uuid",
										VRSessionContext.accessContextAdmin);
							} catch (VException x) {
								HostImpl.me.getLogger().warn("ScribusService : can not document by uuid "+uuid+" | "+x);
							}
							if (file == null) {
								// DELETED FILE TODO CLEANUP + ERROR LOG
								continue;
							}
							// String ext =
							// vd.getMember("extension").getMember("code").asString().toLowerCase();
							// -----------------------------------------------------------------------------
							// String srcext = file.getName().substring(file.getName().lastIndexOf('.')+1);
							String outext = null;
							Value x = e.getMember("initial_value");
							if (x != null && !x.isNull()) {
								Value ext = x.getMember("extension");
								if (ext != null && !ext.isNull())
									outext = ext.getMember("code").asString().toLowerCase();
							} else {
								String pfx = code + "." + lang;
								File f1 = new File(tmpDir, pfx + ".png");
								if (!f1.exists()) {
									f1 = new File(tmpDir, pfx + ".jpg");
									if (!f1.exists()) {
										f1 = new File(tmpDir, pfx + ".pdf");
										if (!f1.exists()) {
											outext = "jpeg";
											f1 = new File(tmpDir, pfx + ".jpeg");
										} else {
											outext = "pdf";
										}
									} else
										outext = "png";
								} else
									outext = "png";
							}
							// -----------------------------------------------------------------------------
							if (outext == null) { // e.getMember("code").asString()
								outext = "png";// TODO AUTODETECT IF INITIAL NULL (NOT VALID ?)
							}
							// -----------------------------------------------------------------------------
							File outImage = new File(tmpDir, code + "." + lang + "." + outext);
							if (isDisabled) {
								if (outext.equalsIgnoreCase("PDF")) {
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
								if (region == null || region.isNull()) {
									if (!Utils.isImage(file.getName())) {
										HostImpl.me.getLogger().error("Trying to add non image file as image : "
												+ file.getPath() + " >> " + outImage.getPath());
									} else {
										try {
											FileUtils.copyFile(file, /* dest */outImage);
										} catch (IOException e1) {
											HostImpl.me.getLogger().error(e1);
											;
										}
									}
								} else {
									try {
										Utils.copyImageInTempDirectory(vd.getMember("uuid").asString(),
												outImage /* dest */, region, VRSessionContext.accessContext);
									} catch (IOException e1) {
										throw new VException(e1);
									}
									// JSCORE.Exec.callVSC("java.util.copyImageInTempDirectory","rndr-"+tmpl.document.id,
									// d, e.code,region);
								}
							}
						}
					}
				}
				/*
				 * { console.warn("render.js : document [" + e.code +
				 * "] is not a db.documents.file instance!"); }
				 */
			} else if ("qrcode".equals(type) || inherits(e, "web2print", "qrcode_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull())
					vd = e.getMember("initial_value");
				if (vd != null && !vd.isNull()) {
					if (!doNotRender) {
						// common
						byte[] qd = BarCode.encode(vd.asString(), "QR_CODE", 256, 256, "#000000",
								"#00000000"/* alpha */);
						try {
							Files.write(/* dest */new File(tmpDir, code + "." + lang + ".png").toPath(), qd);
						} catch (IOException e1) {
							throw new VException(e1);
						}
					} else {
						toReplace.putMember(code, vd);
					}
				}
			} else if ("indexed_color".equals(type) || inherits(e, "web2print", "indexed_color_content")) {
				Value vd = data.getMember(code);
				if (vd == null || vd.isNull()) {
					vd = e.getMember("initial_value");
					if (vd == null || vd.isNull())
						toReplace.putMember(code, "");
					else
						toReplace.putMember(code, vd.getMember("value_cmyk"));
				}
				else {
					String d = vd.getMember("cmyk").asString();
					toReplace.putMember(code,d);
				}
			} else if ("table".equals(type) || inherits(e, "web2print", "table_content")) {
				Value atd = e.getMember("table_data");
				if (!atd.isNull()) {
					Value _td = JSEngine.jsonParse(atd.asString());
					Value td = JSConverter.transportJSON2JS(_td);
					Value columns = td.getMember("columns");
					Value bdata = data.getMember(code);
					if (bdata == null || bdata.isNull()) {
						bdata = td.getMember("data");
						if (bdata == null) { // fallback for temporary preview on admin insert template
							Value za = td.getMember(DBLang.g(VRSessionContext.getCurrentLang()).getCode());
							columns = za.getMember("columns");
							bdata = za.getMember("data");
						}
					}
					for (var col = 0; col < (int) columns.getArraySize(); col++) {
						Value c = columns.getArrayElement(col);
						Value n = c.getMember("name");
						if (n != null && n.isString()) { // COLUMN NAME AS A0,B0,C0..
							String key = code + "." + (char) (65 + col) + "0";
							toReplace.putMember(key, n.asString());
						}
					}
					for (int row = 0; row < (int) bdata.getArraySize(); row++) {
						Value r = bdata.getArrayElement(row);
						for (var col = 0; col < (int) r.getArraySize(); col++) {
							Value val = r.getArrayElement(col);
							String key = code + "." + (char) (65 + col) + (row + 1);
							String sval = "";
							try {
								Value cd = columns.getArrayElement(col);
								String ctype = cd.getMember("type").asString();
								Value t = cd.getMember("suffix");
								String suffix = t != null && !t.isNull() ? t.asString() : "";
								t = cd.getMember("prefix");
								String prefix = t != null && !t.isNull() ? t.asString() : "";
								if (!suffix.isEmpty())
									suffix = " " + suffix;
								if (!prefix.isEmpty())
									prefix += " ";
								if (!val.isNull()) {
									if (!val.isString()) {
										Object v;
										switch (ctype) {
										case "double":
											v = val.isNumber()
													? Common.convertValueByCoreFormat("default_output_format_double",
															JSConverter.JS2VR(val.asDouble()), null)
													: "";
											break;
										case "integer":
											v = val.isNumber()
													? Common.convertValueByCoreFormat("default_output_format_integer",
															JSConverter.JS2VR(val.asLong()), null)
													: "";
											break;
										case "time":
											v = val.isDate()
													? Common.convertValueByCoreFormat("default_output_format_time",
															JSConverter.JS2VR(val.as(Date.class)), null)
													: "";
											break;
										case "hoursMinutes":
											v = val.isDate() ? Common.convertValueByCoreFormat(
													"default_output_format_hours_minutes",
													JSConverter.JS2VR(val.as(Date.class)), null) : "";
											break;
										case "date":
											v = val.isDate()
													? Common.convertValueByCoreFormat("default_output_format_date",
															JSConverter.JS2VR(val.as(Date.class)), null)
													: "";
											break;
										case "datetime":
										case "datetimeHoursMinutes":
											v = val.isNumber() ? Common.convertValueByCoreFormat(
													"default_output_format_datetime_hour_minutes",
													JSConverter.JS2VR(val.as(Date.class)), null) : "";
											break;
										default:
											v = val.toString();
										}
										if (v == null)
											v = "";
										sval = prefix + v + suffix;
									} else {
										sval = val.asString();
									}
									// log.warn(type+" | "+key+" | "+sval);
								}
							} catch (Exception ex) {
								HostImpl.me.getLogger().error(ex);
							}
							toReplace.putMember(key, sval);
						}
					}
				}
			}
			// --------------------------------------------------
			// EMBEDDED
			// --------------------------------------------------
			if (isEmbdVar && !doNotRender) {
				String pfx = code.substring(0, xc);
				Value x = toReplace.getMember(code);
				toReplace.removeMember(code);
				if (x.isString() && x.asString().isBlank())
					x = null;
				if (x != null && !x.isNull()) {
					if (pfx.endsWith(".docx") || pfx.endsWith(".xlsx")) {
						embeddedSet.add(pfx);
						String sfx = code.substring(xc + 1);
						code2Embedded.put(sfx, pfx);
						code2EmbeddedVal.put(sfx, x.asString());
						// ------------------------------
						// pfx : file1.docx/VARNAME

					}
				}
			}
		}
		// ------------------------------------------------------
		// CONVERT EMBEDDED
		// ------------------------------------------------------
		if (!doNotRender) {
			for (String e : embeddedSet) {
				int li = e.lastIndexOf('.');
				String p1 = e.substring(0, li);
				String pe = e.substring(li);
				File f = new File(tmpDir, p1 + "." + lang + pe);
				if (!f.exists()) {
					f = new File(tmpDir, p1 + "." + defaultLangStr + pe);
					if (!f.exists())
						continue;
				}
				String tmpn = p1 + "." + lang + ".tmp" + pe;
				File outfile = new File(tmpDir, tmpn);
				outfile.delete();
				transformDOC(f, outfile, code2EmbeddedVal);
				File tmpf = new File(HostImpl.me.OOConvert(outfile, "pdf", 0).absolutePath);
				String fpdf = p1 + "." + lang + pe + ".pdf";
				File outpdf = new File(tmpDir, fpdf);
				outpdf.delete();
				tmpf.renameTo(outpdf);

				Value rect = emds.getMember(fpdf);
				if (rect == null)
					continue;

				double sx = rect.getMember("sx").asDouble();
				double sy = rect.getMember("sy").asDouble();
				double tx = rect.getMember("tx").asDouble();
				double ty = rect.getMember("ty").asDouble();

				PDDocument d;
				try {
					String fp = f.getPath();
					int lx = fp.lastIndexOf('.');
					fp = fp.substring(0, lx) + fp.substring(lx) + ".pdf";
					d = Loader.loadPDF(new File(fp));
					try {
						Location[] locs = PDFEmbeddedLocations.getLocations(d, null);
						if (locs != null) {
							for (Location l : locs) {
								Value a = JSEngine.newEmptyObject();
								a.putMember("x", l.x * sx + tx);
								a.putMember("y", l.y * sy + ty);
								a.putMember("w", l.width * sx);
								a.putMember("h", l.height * sy);
								a.putMember("page", l.page);
								a.putMember("code", e + "/" + l.code);
								regions.setArrayElement(regions.getArraySize(), a);
							}
						}
					} finally {
						d.close();
					}
				} catch (Exception x) {
					HostImpl.me.getLogger().warn("PDF FILE : can not get embedded locations : " + x);
				}
			}
		}
		//HostImpl.me.getLogger().warn(">>> TO REPLACE >>> "+JSEngine.jsonStringify(toReplace).asString());
		return toReplace;
	}

	@HostAccess.Export
	public void resetTmpDir() {
		Utils.resetTmpDir();
	}

	@HostAccess.Export
	public Value renderTemplate(Value tmpl, Value data, String _lang) throws VException {
		// check for cached document
		if (_lang == null)
			_lang = DBLang.g(VRSessionContext.getCurrentLang()).getCode();
		final String lang = _lang;
		final String ckey = getCachedResultKey(tmpl, data, lang);
		Value cval = Utils.getCachedResult(ckey);
		if (cval != null) {
			String duuid = cval.getMember("uuid").asString();
			// ADD TEMP READ
			HostImpl.me.addDocumentTempReadAccessSession(null/* current session */, duuid);
			return cval;
		}
		// ------------------------------------------------------------------------------
		final String key = "rndr-" + tmpl.getMember("document").getMember("id").asLong();

		Callback cb = new Callback() {
			@Override
			public Value execute() throws VException {
				String name = tmpl.hasMember("name") ? tmpl.getMember("name").asString() : null;
				String code = tmpl.getMember("code").asString();
				File tmpdir = syncTemplate(tmpl);
				// ---------------------------------------------
				String path = resolve(key, "template." + lang + ".sla");
				File f = new File(path);
				if (!f.exists()) {
					path = resolve(key, "template." + defaultLangStr + ".sla");
					f = new File(path);
				}
				// ---------------------------------------------
				Value slat = getSLATemplateLocations(f);
				
				Value regions = slat.getMember("regs"); // only SLA regions, embedded come later
				//Value regions = JSEngine.newEmptyArray();
	
				
				Value emds = slat.getMember("emds"); // embedded documents location
				// ---------------------------------------------
				Value toReplace = _getTemplateJSON(tmpl, data, lang, tmpdir, regions, emds);
				String json = JSEngine.jsonStringify(toReplace).asString();
				Value result = convert(f, key, (name == null ? code : name), lang, json, regions);
				Value resdoc = result == null || result.isNull() ? null : result.getMember("doc");
				if (resdoc == null || resdoc.isNull()) {
					HostImpl.me.getLogger().warn("render.js: Scribus generator returned FIRST EMPTY result : " + code);
					result = convert(f, key, tmpl.getMember("toString").execute().asString(), lang,
							JSEngine.jsonStringify(toReplace).asString(), regions);
					if (result == null || result.isNull())
						HostImpl.me.getLogger().error("!!!! Scribus generator returned EMPTY result : " + code);
				} else {
					String uuid = resdoc.getMember("uuid").asString();
					File outf = HostImpl.me.resolveResourceURL("/documents/" + uuid + ".uuid",
							VRSessionContext.accessContextAdmin);
					ObjectReference resr = resdoc.getMember("_ref").as(ObjectReference.class);
					Utils.putCachedResult(ckey, outf, resr, result.getMember("regs"));
					// ADD TEMP READ
					String duuid = (String) resr.getObject().getSimpleValueByCodeUnsafe("uuid");
					HostImpl.me.addDocumentTempReadAccessSession(null/* current session */, duuid);
				}
				return result;
			}
		};
		return Locker.executeInTempFileLock(key + "." + lang, cb);
	}

	private ObjectLongHashMap<String> lastUpdateTimes = new ObjectLongHashMap();

	private static boolean inherits(Value vd, String module, String objectdef) {
		if (!JSEngine.isInstance(vd))
			return false;
		DBObjectDef ko = DBObjectDef.g(vd.getMember("SCHEMA").getMember("KEY").asString());
		return DBModule.g(module).getSchemaByCode(objectdef).allInheritedFromContains(ko);
	}

	private final static String _px1 = RuntimeConfig.projectDir + "/work/tmp/zip/";

	private static String resolve(String key, String sfx) {
		if (sfx != null)
			return _px1 + key + "/" + sfx;
		else
			return _px1 + key;
	}
	// -----------------------------------------------------------------------------------------------------------------

	/* returns outdir */
	private File syncTemplate(Value tmpl) throws VException {
		Value doc = tmpl.getMember("document");
		final String key = "rndr-" + doc.getMember("id").asLong();
		long upd = tmpl.hasMember("update_time") ? tmpl.getMember("update_time").as(Date.class).getTime() : 1;
		long vup = lastUpdateTimes.get(key);
		File outdir = new File(resolve(key, null));
		if (vup != upd || !outdir.exists()) {
			if (doc != null && !doc.isNull()) {
				// NEEDS REFRESH
				forceStop(key);
				HostImpl.me.getLogger()
						.warn("render.js: updating -> service key [" + key + "]\n\t update time [" + upd
								+ "]\n\t template [" + tmpl.getMember("code").asString() + "]\n\t file ["
								+ doc.getMember("toString").execute().asString() + "]");
				HostImpl.me.getLogger()
						.warn("java.util.extractInTempDirectory, " + key + ", " + doc.getMember("toString").execute());

				String uuid = doc.getMember("uuid").asString();
				File file = HostImpl.me.resolveResourceURL("/documents/" + uuid + ".uuid",
						VRSessionContext.accessContextAdmin);

				File outDirTmp = new File(outdir.getParentFile(), outdir.getName() + ".tmp");
				if (outDirTmp.exists())
					Utils.deleteDir(outDirTmp);
				outDirTmp.mkdirs();
				Utils.unzip(file, outDirTmp);
				if (outdir.exists())
					Utils.deleteDir(outdir);
				outDirTmp.renameTo(outdir);
				HostImpl.me.getLogger().info("render.js: content extracted in temp " + outdir);
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

	private final static byte[] emptyPNG = new byte[] { (byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47, (byte) 0x0d,
			(byte) 0x0a, (byte) 0x1a, (byte) 0x0a, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0d, (byte) 0x49,
			(byte) 0x48, (byte) 0x44, (byte) 0x52, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x01, (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x25, (byte) 0xdb, (byte) 0x56, (byte) 0xca, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x03,
			(byte) 0x50, (byte) 0x4c, (byte) 0x54, (byte) 0x45, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xa7,
			(byte) 0x7a, (byte) 0x3d, (byte) 0xda, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x74,
			(byte) 0x52, (byte) 0x4e, (byte) 0x53, (byte) 0x00, (byte) 0x40, (byte) 0xe6, (byte) 0xd8, (byte) 0x66,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x0a, (byte) 0x49, (byte) 0x44, (byte) 0x41, (byte) 0x54,
			(byte) 0x08, (byte) 0xd7, (byte) 0x63, (byte) 0x60, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x02,
			(byte) 0x00, (byte) 0x01, (byte) 0xe2, (byte) 0x21, (byte) 0xbc, (byte) 0x33, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x49, (byte) 0x45, (byte) 0x4e, (byte) 0x44, (byte) 0xae, (byte) 0x42,
			(byte) 0x60, (byte) 0x82 };
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
			%EOF"""
			.getBytes(StandardCharsets.UTF_8);

	// -----------------------------------------------------------------------------------------------------------------
	private String getCachedResultKey(Value tmpl, Value data, String lang) throws VException {
		long upd = tmpl.hasMember("update_time") ? tmpl.getMember("update_time").as(Date.class).getTime() : 1;
		Value toReplace = _getTemplateJSON(tmpl, data, lang, null, null, null);
		return HostImpl.me.getSHA256(upd + ":" + JSEngine.jsonStringify(toReplace).asString()).replace('/', '$') + "."
				+ lang + ".txt";
	}

	// TODO EXTRACT ONLY FOR LANG !!!! TODO TODO TODO TODO
	private Value convert(File templateFile, String tmpDirKey, String name, String lang, String encodedJSON,
			Value regions) throws VException {
		String opath = resolve(tmpDirKey, "result." + lang + ".pdf");
		File of = new File(opath);
		if (!templateFile.exists())
			return returnErrorAsDocument(of, name);
		//-----------------------------------------------------------------------------------
		/*boolean doneLocs=false; 
		File templateLocsCached = new File(templateFile.getPath()+".locs.json");
		if (templateLocsCached.canRead()) {
			String s;
			try {
				s = Files.readString(templateLocsCached.toPath(), StandardCharsets.UTF_8);
				Value v = JSEngine.jsonParse(s);
				for (int i=0;i<v.getArraySize();i++) 
					regions.setArrayElement(regions.getArraySize(), v.getArrayElement(i));
				doneLocs=true;
			} catch (IOException e) {
				templateLocsCached.delete();
			}
		}
		boolean doneLocs=true;*/
		//-----------------------------------------------------------------------------------
		
		int ntry = 0;
		ProcessInfo pi = null;
		for (; ntry < 20; ntry++) {
			pi = checkProcess(tmpDirKey, lang, true);
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
						if (result.equals("DONE")) {
							// EXTRACT LOCATIONS
							/*if (!doneLocs) {
								PDDocument d = Loader.loadPDF(of);
								try  {
									Location[] locs = PDFEmbeddedLocations.getLocations(d, null);
									Value t = JSEngine.newEmptyArray();
									for (Location l : locs) {
										Value a = JSEngine.newEmptyObject();
										a.putMember("x", l.x);
										a.putMember("y", l.y);
										a.putMember("w", l.width);
										a.putMember("h", l.height);
										a.putMember("page", l.page);
										a.putMember("code", l.code);
										t.setArrayElement(t.getArraySize(), a);
										regions.setArrayElement(regions.getArraySize(), a);
									}
									Files.writeString(templateLocsCached.toPath(), JSEngine.jsonStringify(t).asString(), StandardCharsets.UTF_8);
								} finally {
									d.close();
								}
							}*/
							return returnFileAsFocument(of, name, regions);
						}
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

	private static void transformDOC(File inputfile, File outputFile, Map<String, String> toReplace) {
		InputStream fs = null;
		OutputStream os = null;
		XWPFDocument doc = null;
		try {
			try {
				fs = new FileInputStream(inputfile);
			} catch (FileNotFoundException e) {
				HostImpl.me.getLogger().error("Can not open file for DOC transformation : " + e);
				return;
			}
			try {
				doc = new XWPFDocument(fs);
				HashMap<String, Vector<CTText>> ttxt = new HashMap();
				for (int i = 0; i < doc.getParagraphs().size(); i++) {
					XWPFParagraph paragraph = doc.getParagraphs().get(i);
					List<XWPFRun> runs = paragraph.getRuns();
					for (XWPFRun run : runs) {
						if (run instanceof XWPFHyperlinkRun) {
							XWPFHyperlinkRun hyperlinkRun = (XWPFHyperlinkRun) run;
							String hyperlinkId = hyperlinkRun.getHyperlinkId();
							if (hyperlinkId != null) {
								XWPFHyperlink hyperlink = doc.getHyperlinkByID(hyperlinkId);
								if (hyperlink != null) {
									String url = hyperlink.getURL(); // code = url
									String val = toReplace.get(url);
									if (val != null) {
										// String v = StringUtils.escapeXML(val);
										Vector<CTText> ta = ttxt.get(url);
										if (ta == null) {
											ta = new Vector();
											ttxt.put(url, ta);
										}
										for (CTText g : hyperlinkRun.getCTR().getTList())
											ta.add(g);
									}
								}
							}
						}
					}
				}
				for (Entry<String, Vector<CTText>> en : ttxt.entrySet()) {
					String val = toReplace.get(en.getKey());
					Vector<CTText> arr = en.getValue();
					for (int p = 0; p < arr.size(); p++) {
						CTText c = arr.get(p);
						if (val.isEmpty())
							c.setStringValue("");
						else if (p == arr.size() - 1) {
							// LAST
							c.setStringValue(val);
						} else {
							String t = c.getStringValue();
							if (t.isEmpty())
								continue;
							int l = Math.min(t.length(), val.length());
							c.setStringValue(val.substring(0, l));
							if (l == val.length())
								val = "";
							else
								val = val.substring(l);
						}
					}

				}

				os = new FileOutputStream(outputFile);
				doc.write(os);
			} catch (FileNotFoundException e) {
				HostImpl.me.getLogger().error("Can not write file for DOC transformation : " + e);
			} catch (IOException e) {
				HostImpl.me.getLogger().error("IOException in DOC transformation : " + e);
			}
		} finally {
			if (doc != null) {
				try {
					doc.close();
				} catch (IOException e) {
				}
			}
			if (fs != null) {
				try {
					fs.close();
				} catch (IOException e) {
				}
			}
			if (os != null) {
				try {
					os.close();
				} catch (IOException e) {
				}
			}
		}
	}

	public static void docxExtractInitialValues(File f, String lang,
			Map<String, Map<String, String>> code2InitialByLang, String pfx) {
		InputStream fs = null;
		XWPFDocument doc = null;
		try {
			try {
				fs = new FileInputStream(f);
			} catch (FileNotFoundException e) {
				HostImpl.me.getLogger().error("Can not open file for DOC transformation : " + e);
				return;
			}
			try {
				doc = new XWPFDocument(fs);
				for (int i = 0; i < doc.getParagraphs().size(); i++) {
					XWPFParagraph paragraph = doc.getParagraphs().get(i);
					List<XWPFRun> runs = paragraph.getRuns();
					for (XWPFRun run : runs) {
						if (run instanceof XWPFHyperlinkRun) {
							XWPFHyperlinkRun hyperlinkRun = (XWPFHyperlinkRun) run;
							String hyperlinkId = hyperlinkRun.getHyperlinkId();
							if (hyperlinkId != null) {
								XWPFHyperlink hyperlink = doc.getHyperlinkByID(hyperlinkId);
								if (hyperlink != null) {
									String code = pfx + "/" + hyperlink.getURL(); // code = url

									Map<String, String> m = code2InitialByLang.get(code);
									if (m == null) {
										m = new HashMap();
										code2InitialByLang.put(code, m);
									}
									StringBuilder sb = new StringBuilder();
									for (CTText g : hyperlinkRun.getCTR().getTList())
										sb.append(g.getStringValue());
									String text = sb.toString();
									if (!_isPlaceholderString(text)) {
										m.put(lang, text);
									}
								}
							}
						}
					}
				}

			} catch (FileNotFoundException e) {
				HostImpl.me.getLogger().error(e);
			} catch (IOException e) {
				HostImpl.me.getLogger().error("IOException in docxExtractInitialValues : " + e);
			}
		} finally {
			if (doc != null) {
				try {
					doc.close();
				} catch (IOException e) {
				}
			}
			if (fs != null) {
				try {
					fs.close();
				} catch (IOException e) {
				}
			}
		}
	}

	public static boolean _isPlaceholderString(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c))
				continue;
			if (Character.isAlphabetic(c))
				return false;
			if (Character.isDigit(c))
				return false;
			switch (c) {
			case '…':
			case '-':
			case '.':
			case '_':
				continue;
			}
			return false;
		}
		return true;
	}

	// result : .emds, .regs
	public static Value getSLATemplateLocations(File templateFile) throws VException {
		File cached = new File(templateFile.getPath() + ".loc.json");
		if (cached.canRead()) {
			try {
				String s = Files.readString(cached.toPath(), StandardCharsets.UTF_8);
				return JSEngine.jsonParse(s);
			} catch (IOException e) {
				cached.delete();
			}
		}
		Value regs = JSEngine.newEmptyArray();
		Value emds = JSEngine.newEmptyObject();

		final HashMap<Integer, double[]> docDimByPage = new HashMap();
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder;
		Document document;
		try {
			builder = factory.newDocumentBuilder();
			document = builder.parse(templateFile);
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new VException(e);
		}
		final double bleedTopLeftCxCy[] = new double[2];
		// ADD EMBEDDED DOCUMENTS
		SLAXML.walkAll(document, new XMLHandler() {
			@Override
			public void handleNode(Node node) throws VException {

				switch (node.getNodeName()) {
				case "PDF":
					bleedTopLeftCxCy[0] = Double.parseDouble(node.getAttributes().getNamedItem("BTop").getNodeValue());;
					bleedTopLeftCxCy[1] = Double.parseDouble(node.getAttributes().getNamedItem("BLeft").getNodeValue());;
					break;
				case "PAGE":
					double docDim[] = new double[4];
					docDim[0] = Double.parseDouble(node.getAttributes().getNamedItem("PAGEWIDTH").getNodeValue());
					docDim[1] = Double.parseDouble(node.getAttributes().getNamedItem("PAGEHEIGHT").getNodeValue());
					docDim[2] = Double.parseDouble(node.getAttributes().getNamedItem("PAGEXPOS").getNodeValue());
					docDim[3] = Double.parseDouble(node.getAttributes().getNamedItem("PAGEYPOS").getNodeValue());
					docDimByPage.put(docDimByPage.size(), docDim);
					break;
				case "PAGEOBJECT":
					Node pf = node.getAttributes().getNamedItem("PFILE");
					if (pf == null)
						return;
					String filen = pf.getNodeValue();
					if (!filen.endsWith(".docx.pdf") && !filen.endsWith(".xlsx.pdf"))
						return;
					Node nx = node.getAttributes().getNamedItem("XPOS");
					if (nx == null)
						return;
					Node ny = node.getAttributes().getNamedItem("YPOS");
					if (ny == null)
						return;
					Node nw = node.getAttributes().getNamedItem("WIDTH");
					if (nw == null)
						return;
					Node nh = node.getAttributes().getNamedItem("HEIGHT");
					if (nh == null)
						return;
					Node op = node.getAttributes().getNamedItem("OwnPage");
					if (op == null)
						return;
					/* EMBEDDED !!!! */
					double[] dd = docDimByPage.get(Integer.parseInt(op.getNodeValue()));
					double x = Double.parseDouble(nx.getTextContent());
					double y = Double.parseDouble(ny.getTextContent());
					double w = Double.parseDouble(nw.getTextContent());
					double h = Double.parseDouble(nh.getTextContent());
					
					x-=dd[2];
					y-=dd[3];
					x+=bleedTopLeftCxCy[1];
					y+=bleedTopLeftCxCy[0];
					
					Value t = JSEngine.newEmptyObject();
					t.putMember("sx", w / dd[0]); // 1.scalex
					t.putMember("sy", h / dd[1]); // 1.scaley
					t.putMember("tx", x); // 2.translateX
					t.putMember("ty", y); // 2.translateY
					emds.putMember(filen, t);
					break;
				}
			}
		});
		SLAXML.walkContents(document, new ContentHandler() {
			@Override
			public void handleContent(String code, String text, int page, Node node) throws VException {
				Node nx = node.getAttributes().getNamedItem("XPOS");
				if (nx == null)
					return;
				Node ny = node.getAttributes().getNamedItem("YPOS");
				if (ny == null)
					return;
				Node nw = node.getAttributes().getNamedItem("WIDTH");
				if (nw == null)
					return;
				Node nh = node.getAttributes().getNamedItem("HEIGHT");
				if (nh == null)
					return;
				double x = Double.parseDouble(nx.getTextContent());
				double y = Double.parseDouble(ny.getTextContent());
				double w = Double.parseDouble(nw.getTextContent());
				double h = Double.parseDouble(nh.getTextContent());
				double[] dd = docDimByPage.get(page - 1);
				
				x -= dd[2];
				y -= dd[3];
				
				x+=bleedTopLeftCxCy[1];
				y+=bleedTopLeftCxCy[0];

				Value a = JSEngine.newEmptyObject();
				a.putMember("x", x);
				a.putMember("y", y);
				a.putMember("w", w);
				a.putMember("h", h);
				a.putMember("page", page - 1);
				a.putMember("code", code);
				regs.setArrayElement(regs.getArraySize(), a);
			}
		});

		Value res = JSEngine.newEmptyObject();
		res.putMember("regs", regs);
		res.putMember("emds", emds);
		try {
			Files.writeString(cached.toPath(), JSEngine.jsonStringify(res).asString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new VException(e);
		}
		return res;
	}
	 public static String cmykToRgb(double c, double m, double y, double k) {
        double cD = c / 100.0;
        double mD = m / 100.0;
        double yD = y / 100.0;
        double kD = k / 100.0;

        int r = (int) (255 * (1 - cD) * (1 - kD));
        int g = (int) (255 * (1 - mD) * (1 - kD));
        int b = (int) (255 * (1 - yD) * (1 - kD));
        return "("+r+","+g+","+b+")";
	 }
	 //-----------------------------------------------------------------------------------
	 private static Thread cleanupThread = null;
	 private static final Runnable cleanupTask = ()->{
		 final HashMap<String,ProcessInfo> expired = new HashMap();
		 while (true) {
			 try 
			 {
				 final long now = System.currentTimeMillis();
				 synchronized (processes) {
					 for (Entry<String,ProcessInfo> e : processes.entrySet()) 
						 if (e.getValue().expired(now)) 
							 expired.put(e.getKey(),e.getValue());
					 for (Entry<String,ProcessInfo> e : expired.entrySet()) 
						 processes.remove(e.getKey());
				 }
				 for (ProcessInfo e : expired.values()) {
					 Process p = e.process;
					 Socket s = e.clientSocket;
					 try {
						if ( s != null)
							s.close();
					} catch (IOException e1) {
						HostImpl.me.getLogger().error(e1);
					}
					if (p != null)
						p.destroyForcibly();
					//HostImpl.me.getLogger().warn(">> TERMINATED PROCESS "+(p == null ? "null" : p.pid()));
				 }
				 expired.clear();
				 Thread.sleep(2000);
			} catch (InterruptedException e) {
				HostImpl.me.getLogger().error(e);			
			}
		 }
	 };
}
