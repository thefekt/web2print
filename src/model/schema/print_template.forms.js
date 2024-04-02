/* visionr-engine backend js , not executed during model compile */

const misc = require("server/misc");
const forms = require("server/forms");

require("./print_template.forms.remote");

/* FORM DEFS */
forms.define({
	"web2print.print_template" :  
	{    
	    view : 'tiles', 
	    views : ['table','tiles','overview'],
	    viewParams : 
	    { 
	        edit : {
	            columns : 'code,name,description,contents,uuid,document,width_mm,height_mm'
	        }
	    }, 
	    properties :
	    { 
	        document : { 
	            autocomplete : false,
	            obligatory : false, 
	            hidden : commonHideIfNew
	        },
	        contents : {
	            hidden : hideIfNoDocument
	        },  
	        contents : {  
	            hidden : hideIfNoDocument
	        },
	        width_mm : {
	            hidden : hideIfNoDocument
	        },
	        height_mm : { 
	            hidden : hideIfNoDocument
	        }  
	    },
	    fileDrop : function(details) {
	        return {
	            ref : "/dialogs/web2print/print_template/create-from-file",
	            params :  {
	                object : details.object
	            }
	        }
	    }, 
	    objectDocuments : function(details) {
			var object = details.object;
	        var res=[];
	        if (object.preview_document && !object.preview_document.DELETED) 
	            res.push(object.preview_document);
	        for (var c of object.contents||[]) 
	           if (c instanceof db.web2print.image_content && c.initial_value instanceof db.documents.document && !c.DELETED) 
	                res.push(c.initial_value);
			return res;
		},
		objectIcon : function(details) {
			var obj = details.object;
			if  (!obj) return;
			var pdoc = obj.preview_document;
			if (pdoc) 
				return misc.IMG(pdoc,details.width,details.height,details.renderer);
		}
	}
});

/* END OF FORMS DEF */

function commonHideIfNew(details) {
    if (details.transaction != "insert")
        return false;
    return true; 
}  

function hideIfNoDocument(details) {
    if (commonHideIfNew(details)) 
    	return true;
    var object = details.object;
    if (!object) 
    	return true;
    return !object.document;
}