package com.planvision.web2print;

import java.io.File;
import java.io.IOException;


import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.planvision.visionr.core.VException;
import com.planvision.visionr.core.api.HostImpl;

/* scribus XML format functions */
public final class SLAXML {

	public static interface ContentHandler {
		public abstract void handleContent(String code,String text,int page,Node node) throws VException;
	}
	
	public static interface XMLHandler {
		public abstract void handleNode(Node node) throws VException;
	}
	
	public static void walkContents(File f,ContentHandler hndlr) throws VException {
	    
	    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder;
		try {
			builder = factory.newDocumentBuilder();
			Document document = builder.parse(f);
			walkContents(document.getFirstChild(),hndlr);
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new VException(e);
		}
		
	}
	
	public static void walkAll(File f,XMLHandler hndlr) throws VException {
		    
		    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder;
			try {
				builder = factory.newDocumentBuilder();
				Document document = builder.parse(f);
				walkAll(document.getFirstChild(),hndlr);
			} catch (ParserConfigurationException | SAXException | IOException e) {
				throw new VException(e);
			}
			
		}
	public static void walkAll(Node node,XMLHandler hndlr) throws VException {
		hndlr.handleNode(node);
		if (node.getFirstChild() != null)
			walkAll(node.getFirstChild(),hndlr);
		if (node.getNextSibling() != null)
			walkAll(node.getNextSibling(),hndlr);
	}
	
	
	
	// TODO PDF ATTRIBUTE CUSTOM CHECK FIRST ? 
	private static void walkContents(Node node,ContentHandler hndlr) throws VException {
		if (node.getNodeName().equals("PAGEOBJECT")) {
			Node anode = node.getAttributes().getNamedItem("ANNAME");
			if (anode != null) {
				String aval = anode.getNodeValue();
				if (aval.startsWith("(") && aval.endsWith(")")) {
					// CONTENT MARKER NODE NAME !!!
					StringBuilder sb = new StringBuilder();
					getText(node.getFirstChild(),sb);
					int page = 0;
					Node ownp = node.getAttributes().getNamedItem("OwnPage");
					if (ownp != null) {
						try {					
							page = Integer.parseInt(ownp.getNodeValue())+1;
						} catch (NumberFormatException e) {
							HostImpl.me.getLogger().warn("SLAXML.walkContents : can not parse PAGEOBJECT OwnPage attribute : "+node.toString());
						}
					}
					hndlr.handleContent(aval.substring(1,aval.length()-1), sb.toString(),page,node);
				}
			}
		}		
		if (node.getFirstChild() != null)
			walkContents(node.getFirstChild(),hndlr);
		if (node.getNextSibling() != null)
			walkContents(node.getNextSibling(),hndlr);
	}
	
	private static void getText(Node node,StringBuilder sb) {
		if (node == null) return;
		if (node.getNodeName().equals("ITEXT")) {
			Node anode = node.getAttributes().getNamedItem("CH");
			if (anode != null) {
				String aval = anode.getNodeValue();
				sb.append(aval);
			}
		} 
		getText(node.getFirstChild(),sb);
		getText(node.getNextSibling(),sb);
	}
}
