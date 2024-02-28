const forms = require("server/forms");
const fncs = require("web2print/schema.fnc");  
const misc = require("server/misc");
forms.define({    
	"web2print.print_template" :  
	{
        view : 'tiles', 
        views : ['table','tiles','overview'],
        viewParams : 
        { 
            edit : {
                columns : 'code,name,description,items,contents,uuid,document,width_mm,height_mm'
            }
        }, 
        properties :
        { 
            document : { 
                autocomplete : false,
                obligatory : false, 
                hidden : fncs.commonHideIfNew
            },
            contents : {
                hidden : fncs.hideIfNoDocument
            },  
            contents : {  
                hidden : fncs.hideIfNoDocument
            },
            width_mm : {
                hidden : fncs.hideIfNoDocument
            },
            height_mm : { 
                hidden : fncs.hideIfNoDocument
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
		}  
    },
    "web2print.abstract_content" : {
        viewParams :
        { 
            edit : {
                columns : 'code,name,initial_value,dest_page,objectdef,available_colors,proportion,region,table_data,documents'
            }
        },
        executions : {
            'select-view-box' : function(details) {
                return {
                    icon : 'border_outer',
                    ref : '/dialogs/web2print/print_template/select-view-box',
                    selection : true,
                    params : {
						fullscreen : true
					}
                    //,params : {}
                };
            }
        }, 
		uploadFolders : function(details) {
			var pt = details?.object?.print_template;
			if (!pt) return;
			if (!session.isAdmin && !pt.ACCESS.owner) return;
			return {
				template : {
					name : misc.MSG("MSG:DEFAULT_FOLDER_TEMPLATE"),
					folder : `/web2print/${pt.uuid}/public`,
					label : `/web2print/${pt.uuid}/public`
				},
				home : true,
				custom : true
			}
		},
		applyUpload : function(details) {
			var object = details.object;
			var document = details.document;
			if (object?.print_template && document) {
				document.access_read=object?.print_template.access_read;
				document.access_update=object?.print_template.access_update;
				document.access_delete=object?.print_template.access_delete;
			}
		}
    },
});
