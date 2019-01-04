var forms = require("server/forms");
//-----------------
function commonHideIfNew(object,details) {
    if (details.transaction != "insert")
        return false;
    return true;
}

function hideIfNoDocument(object,details) {
    if (commonHideIfNew(object,details)) return true;
    return !object.document;
}

//-----------------
forms.define({
	"web2print.print_template" :
	{
        view : 'columns',
        views : ['table','list','columns'],
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
        fileDrop : function(documents,details) {
            if (!documents || !documents.length)
                return;
            if (documents.length > 1) {
                return {
                    dialog : "/dialogs/basic/error",
                    params : {
                        content : 'Only single file (ZIP template) allowed!'
                    }
                }
            }
            var document = documents[0];
            if (!document || !document.extension || document.extension.code != 'ZIP')
            return {
                dialog : "/dialogs/basic/error",
                params : {
                    content : 'Only ZIP templates allowed!'
                }
            }
            //-------------------------------------------------------------
            return {
                dialog : "/dialogs/web2print/print_template/create-from-file",
                params :  {
                    document : documents[0],
                    object : details.object
                }
            }
        }
    },
    "web2print.abstract_content" : {
        viewParams :
        {
            edit : {
                columns : 'code,name,dest_page,initial_value,dest_page,objectdef,available_colors,proportion,region,table_data'
            }
        },
        executions : {
            'select-view-box' : function(details) {
                return {
                    icon : 'border_outer',
                    ref : '/dialogs/web2print/print_template/select-view-box',
                    selection : true,
                    params : {
                        test : 'TEST!'
                    }
                };
            }
        }
    }
});
