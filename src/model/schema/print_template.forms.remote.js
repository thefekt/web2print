const forms = require("server/forms");
/* REMOTE APIS (client js) | direct assignment not possible (def js compile nodejs runtime not server) */
forms.defineRemoteAPI({ 
	/*"w2p.remote" : { 			// optional custom inlined implementation , example : forward
		renderTemplate() {
			require("web2print/renderer").renderTemplate.apply(null,arguments); // FORWARD 
		},
		renderTemplateOnce() {
			require("web2print/renderer").renderTemplateOnce.apply(null,arguments); // FORWARD 
		},
		createTemplate(){
			require("web2print/template").createTemplate.apply(null,arguments); // FORWARD 
		},
		getTemplateDetails(){
			require("web2print/template").getTemplateDetails.apply(null,arguments); // FORWARD 
		},
		uploadUserDocument() {
			require("web2print/template").uploadUserDocument.apply(null,arguments); // FORWARD 
		},
		getInitialImageRegion() {
			require("web2print/template").getInitialImageRegion.apply(null,arguments); // FORWARD 
		},
		deleteTemplateDocuments() {
			require("web2print/template").deleteTemplateDocuments.apply(null,arguments); // FORWARD 
		},
		initTemplateContentsUploaded() {
			require("web2print/template").initTemplateContentsUploaded.apply(null,arguments); // FORWARD 
		}
	
	}*/
	"w2p.remote" : {	/* forward to backend require */
		renderTemplate : "web2print/renderer",
		renderTemplateOnce : "web2print/renderer",
		createTemplate : "web2print/template",
		getTemplateDetails : "web2print/template",
		uploadUserDocument : "web2print/template",
		getInitialImageRegion : "web2print/template",
		deleteTemplateDocuments : "web2print/template",
		initTemplateContentsUploaded : "web2print/template",
	} 
});
