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
        }
    },
    "web2print.abstract_content" : {
        viewParams :
        {
            edit : {
                columns : 'code,name,dest_page,initial_value'
            }
        }
    }
});
