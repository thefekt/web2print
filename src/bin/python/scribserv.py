#!/usr/bin/python3 
# -*- coding: utf-8 -*-

# ****************************************************************************
#  This program is free software; you can redistribute it and/or modify
#  it under the terms of the GNU General Public License as published by
#  the Free Software Foundation; either version 2 of the License, or
#  (at your option) any later version.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#
#  You should have received a copy of the GNU General Public License
#  along with this program; if not, write to the Free Software
#  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
#
# ****************************************************************************


"""

(C) 2017 by Gheorghi Penkov

see the README.md for usage

"""

# VERSION = "v0.5 - w/working templating"
# VERSION = "v0.6 - w/colors & unit test"
# VERSION = "v0.7 - w/local+remote debug"
# VERSION = "v0.7.2 - w/local+remote debug & even smarter"
# VERSION = "v0.7.3 - replace ftw"
# VERSION = "v0.7.4 - timeouts & opendoc"
VERSION = "v0.7.5 - bugfixing"
# ----------------------------------------------------------------------------

# uncomment this in order to debug remotely
# with winpdb (http://www.winpdb.org/)
# import rpdb2;
# rpdb2.start_embedded_debugger('slivi4smet')

# uncomment this in order to debug remotely
# as descibed in https://donjayamanne.github.io/pythonVSCodeDocs/docs/debugging_remote-debugging/
# # although, not working at the time of writing fo this code

# import ptvsd
# ptvsd.enable_attach("slivi4smet", address=('0.0.0.0', 3040))
# ptvsd.wait_for_attach()

CONNECTION_TIMEOUT = 60
INACTIVE_TIMEOUT = 120
DOCUMENT_TIMEOUT = 120
DEFAULT_PORT = 22022
#'c:/t/t.txt' None
LOGFILE = None

import logging
import re
import socketserver
import urllib
import sys
import json
import socket
# ----------------------------------------------------------------------------

class ScribusDummy(object):
    page = 0

    class ScribusException(Exception):
        pass

    @staticmethod
    def getColorNames():
        print('scribus.getColorNames()')
        return ['{{COLOR1}}', '{{COLOR2}}']

    @staticmethod
    def changeColor(name, c, m, y, k):
        print('scribus.changeColor( "{}", {}, {}, {}, {})'.format(name, c, m, y, k))

    @staticmethod
    def setRedraw(a):
        print('scribus.setRedraw({})'.format(a))

    @staticmethod
    def pageCount():
        print('scribus.pageCount()')
        return 1

    @staticmethod
    def gotoPage(a):
        print('scribus.gotoPage({})'.format(a))

    @staticmethod
    def getAllText(a):
        print('scribus.getAllText("{}")'.format(a))
        return '{{DESC}}'

    @staticmethod
    def setText(nstr, item):
        print('scribus.setText("{}", "{}")'.format(nstr, item))

    @staticmethod
    def getPageItems():
        print('scribus.getPageItems()')
        return [('Image1', 2, 1), ('Text3', 4, 3), ('Text4', 4, 4), ('Image6', 2, 6)]
# ----------------------------------------------------------------------------
try:
    import scribus
    from scribus import PDFfile, haveDoc

    def replaceText(text, code):
        scribus.selectObject(code)

        # In Python 3, comparing with 'is' for strings might not always work as expected.
        # Using '==' is more appropriate for content comparison.
        if text is None or text == '':
            logger.warn(".. empty content for elem [%s]", code)
            text = '\t\t'

        if len(text) == 1:
            text = text + '\t'

        l = scribus.getTextLength(code)
        scribus.selectText(0, l-1, code)
        scribus.deleteText(code)
        scribus.insertText(text, 0, code)

        l = scribus.getTextLength(code)
        scribus.selectText(l-1, 1, code)
        scribus.deleteText(code)

    scribus.replaceText = replaceText

except ImportError:
    # Print is now a function in Python 3
    print('! yo runnin standalone, baba!')
    scribus = ScribusDummy
# ----------------------------------------------------------------------------
logger = logging.getLogger('automator')

if LOGFILE is None:
    hdlr = logging.NullHandler()
else:
    hdlr = logging.FileHandler(LOGFILE)

# ----------------------------------------------------------------------------

formatter = logging.Formatter('%(asctime)s %(levelname)s %(message)s')
hdlr.setFormatter(formatter)
logger.addHandler(hdlr)
logger.setLevel(logging.INFO)

# ----------------------------------------------------------------------------
class TCPServerV4(socketserver.TCPServer):
    address_family = socketserver.socket.AF_INET
    allow_reuse_address = True
    timeout = CONNECTION_TIMEOUT
# ----------------------------------------------------------------------------

def exportPDF(opath='VR_EXPORT.pdf'):
    if 'PDFfile' in globals():
        scribus.docChanged(True)
        pdf = PDFfile()

        # options are described at
        # https://www.scribus.net/svn/Scribus/trunk/Scribus/doc/en/scripterapi-PDFfile.html

        pdf.compress = 0

        pdf.version = 15        # 15 = PDF 1.5 (Acrobat 6)

        pdf.allowPrinting = True
        pdf.allowCopy = True

        pdf.outdst = 0          # out destination - 0 - printer, 1 - web
        pdf.file = opath
        pdf.profilei = True     # embed color profile
        pdf.embedPDF = True     # PDF in PDF
        # pdf.useLayers = True 	# export the layers (if any)
        pdf.fontEmbedding = 1   # text to curves

        # pdf.resolution = 300    # good enough for most prints
        # pdf.quality = 1         # high image quality

        pdf.save()
    else:
        logger.warn('no PDF printing in standalone run')
# ----------------------------------------------------------------------------
def processColors(xlat):
    if xlat is None:
        return
    logger.info('! process colors')

    rclean = re.compile(r'[{}]')
    rcmyk = re.compile(r'[(]*(?:(\d+)[\%\s,]*)[)]*')

    try:
        colcodes = [rclean.sub('', n)
                    for n in scribus.getColorNames()
                    if '{' in n and '}' in n]

        logger.info("..colcodes %s", str(colcodes))

        for i in colcodes:
            if i in xlat:
                logger.info('..%s => %s', i, xlat[i])

        cn = {name: list(map(int, rcmyk.findall(xlat[name])))
              for name in colcodes
              if name in xlat and ',' in xlat[name]}

        logger.info("..colors xlat %s ", str(cn))

        for name, val in cn.items():
            cname = '{{%s}}' % name
            scribus.changeColor(cname, *val)
            logger.info('...replaced color %s => (%s)', cname, xlat[name])

    except scribus.ScribusException as e:
        logger.error('..scribus failed: %s', e)
    except Exception as e:
        logger.error('..standard error: %s', e)
# ----------------------------------------------------------------------------
def SelectAllText(textframe):
	texlen = scribus.getTextLength(textframe)
	scribus.selectText(0,texlen,textframe)
	return 
 
# ----------------------------------------------------------------------------
def processTemplate(xlat):
    if xlat is None:
        return
    
    page = 1;
    
    pagenum = scribus.pageCount();
    while page <= pagenum:
    	logger.info(r'.process page ' + str(page));
    	scribus.gotoPage(page);
    	pitems = scribus.getPageItems();
    	
    	for item in [p for p in pitems if p[1] == 4]:
    		i0 = str(item[0]);
    		if (i0.startswith("(")):
    			code = i0[1:-1];
    			
    			phc = None;
    			
    			if i0 in Automator3.codes:
    				phc = Automator3.codes[i0];
    			else:
    				phc = scribus.getAllText(i0);
    				Automator3.codes[i0] = phc;

    			val = xlat[code];
    			if not val:
    				val = phc;
    				
    			if val:
		    		logger.info(r'..process item: %s : %s ', code,phc);
		    		scribus.deselectAll();
		    		scribus.deleteText(i0);
		    		scribus.insertText(str(val), 0, i0);
    		
    	page += 1;
	
    logger.info('! done processing template');
    return
# ----------------------------------------------------------------------------
class Automator3:    
    def __init__(self, forward):
        self.forward = forward
        self.saved = dict()

    def sendLine(self, line):
        self.forward.sendLine(line)

    def server_close(self):
        logger.info('! finalize work and shutdown.')
        self.forward.shutdown()

    def shutdown(self):
        """Close connection and app.

        Typically in case of inactivity.

        """
        logger.warn('! shutdown system.')
        try:
            # as per http://forums.scribus.net/index.php?topic=1448.0
            import PyQt4.QtGui as gui
            app = gui.QApplication.instance()

            logger.warn('! shutdown server')
            self.forward.connection.close()
            server.shutdown()

            logger.warn('! shutdown app')
            app.exit(0)
        except StandardError:
            logger.warn(r'could not import PyQt4.QtGui. just close all')
            self.foward.connection.close()
            server.shutdown()

    @staticmethod
    def CONVERT(arg):

        split_index = arg.index('|')

        opath = arg[:split_index]
        xlatenc = arg[split_index + 1:]

        logger.info('..opath: [%s]', opath)
        jsxlat = xlatenc
        #logger.info('..json: [%s]', jsxlat)

        try:
            xlat = json.loads(jsxlat)
            #logger.info('..xlat: %s', xlat)
        except Exception as e:
            logger.error('..error decoding json: %s', e)            
            return 'ERR_BAD_JSON: %s' % jsxlat

        # --------------------------------------------------------------------

        processTemplate(xlat)
        processColors(xlat)

        Automator3.EXPORT(opath)

        # scribus.closeDoc()
        return 'DONE'

    @staticmethod
    def EXPORT(opath):
        logger.info('! compositing & exporting... ')
        exportPDF(opath)
        logger.info('! exported PDF to %s', opath)

    @staticmethod
    def OPEN(arg):
        marg = re.search(r'(.*?):(.*)', arg)
        if marg:
            [opath, code] = [marg.group(1), marg.group(2)]
        else:
            logger.error('..bad argument: [%s]', arg)
            return 'ERR_BAD_ARG: %s' % arg

        logger.info('! open document %s, code %s', opath, code)
        try:
            # scribus.closeDoc()
            scribus.openDoc(opath)

        except Exception as e:
            logger.error('.can not open [%s], because %s', opath, e)
            return 'ERR_BAD_OPEN: %s', e

        return 'DONE'

    @staticmethod
    def EXIT(obj):
        logger.warn('! closing remote connection and shutdown server')
        obj.shutdown()

    def lineReceived(self, line):
        """Handle a line recieved from network.

        Dispatch to corresponding handler, as
        described in self.operations.
        """

        mln = re.search(r'(.*?):(.*)', line)
        if mln:
            [code, arg] = [mln.group(1), mln.group(2)]
        else:
            self.sendLine('ERR_BAD_CMD')
            return

        #logger.info('.cmd [%(code)s] && arg [%(arg)s]', {'code': code, 'arg': arg})

        if code == 'CONVERT':
            self.sendLine(Automator3.CONVERT(arg))
        elif code == 'OPEN':
            self.sendLine(Automator3.OPEN(arg))
        elif code == 'EXPORT':
            self.sendLine(Automator3.EXPORT(arg))
        elif code == 'EXIT':
            self.sendLine(Automator3.EXIT(arg))
        else: 
            self.sendLine('UNKNOWN_COMMAND')

        logger.info('! done processing command [%s]', code)

Automator3.codes = dict()

# --------------------------------------------------------------------
# maybe leave this true at some point when
# all development is done
scribus.setRedraw(False)
if len(sys.argv) > 1:
    PORT = int(sys.argv[1])
else:
    PORT = DEFAULT_PORT
# --------------------------------------------------------------------

class SocketRequestHandler(socketserver.BaseRequestHandler):
     
    def sendLine(self, line):
        if line is None:
            self.request.sendall('\n'.encode('utf-8'))
        else:  
            self.request.sendall((line+'\n').encode('utf-8'))  
    
    def handle(self):
        logger.info("HANDLE...")
        impl = Automator3(self)
        logger.info('! handle request. initiate dialogue.')        
        logger.info('! adapter %s', VERSION)

        input_stream = self.request.makefile('r', encoding='utf-8')

        while True:
            # Reading a line of text from the client
            line = input_stream.readline().strip()
            if line is None: 
                break                 
            impl.lineReceived(line)      
        

logger.info("START START START START")
logger.info("START START START START")
logger.info("")

# Create a server, binding to localhost on port PORT
with socketserver.TCPServer(("localhost", PORT), SocketRequestHandler) as server:
    # Activate the server; this will keep running until you interrupt the program with Ctrl+C
    server.serve_forever()

