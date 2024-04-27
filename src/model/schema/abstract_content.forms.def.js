const misc = require("server/misc");
const forms = require("server/forms");

forms.define({
	"web2print.abstract_content" :  
	{      
		buttons : {
			new : false,
			delete : false,
			copy : false
		},
	    viewParams :
	    { 
	        edit : {
	            columns : 'code,name,initial_value,dest_page,objectdef,available_colors,proportion,region,table_data,documents'
	        }
	    },
	    /*executions : {
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
	    },*/ 
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
	}
});