var misc = require("server/misc");

var service = JSCORE.Exec.resolveHostAPI("web2print.ScribusService");

exports.initTemplateContents = service.initTemplateContents;
exports.renderTemplate = service.renderTemplate;
exports.getTemplateJSON = service.getTemplateJSON;
exports.resetTempDir = service.resetTmpDir;
exports.forceStop = function(tmpl) {
    var key =  "rndr-"+tmpl.document.id;
    service.forceStop(key);
};
exports.renderTemplateOnce = function(tmpl,data) {
    try {
        return exports.renderTemplate(tmpl,data);
    } finally {
        exports.forceStop(tmpl);
    }
};

exports.checkPreview = function(tmpl) {
    var data = exports.getTemplateJSON(tmpl,{});
    var key = JSON.stringify(data);
    if (tmpl.preview_document && !tmpl.preview_document.DELETED && key == tmpl.preview_key)
        return;
    tmpl.preview_key=key;
    var oldPd = tmpl.preview_document;
    if (oldPd) {
        tmpl.preview_document=null;
        oldPd.delete();
        oldPd.commit();
    }
    var everyone = db.core.user_role.byCode("everyone");
	var admin = db.core.user_role.byCode("customer:admin");
	var pdoc = tmpl.preview_document = exports.renderTemplate(tmpl,{});
    if (!pdoc)
        return;
    pdoc.access_read=everyone;
	pdoc.access_update=admin;
	pdoc.access_delete=admin;
	pdoc.access_copy=admin;
	pdoc.access_read_session=undefined;
	pdoc.parent = dir;
    //-------------------------------------------
	var dir = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${tmpl.uuid}`})[0];
    if (!dir) throw `can not find document.folder '/web2print/${tmpl.uuid}'` 
    pdoc.parent=dir;
    //-------------------------------------------
    try {
        var dim =JSCORE.Exec.callVSC("java.util.getPDFDimensions",tmpl.preview_document.uuid,0);
        var width = dim[0]*297/847;
        var height = dim[1]*297/847;
        tmpl.width_mm=width;
        tmpl.height_mm=height;
    } catch (e) {
        console.error(e);
    }
    //console.warn(" >> PREVIEW UPDATED : "+tmpl+" | "+key+" | "+tmpl.preview_document);
}

exports.getRootCategoriesWithDetails = function(id)
{
    function rec(e,level)
    {
        var r;
        if (e instanceof db.web2print.tag_category)
        {
            r =
            {
                id : e.id,
                SCHEMA : e.SCHEMA.module.code+"."+e.SCHEMA.code,
                code : e.code,
                NAME : misc.OBJSTR(e),
                children : [],
                tags : []
            };
            if (e.parent)
            {
                r.parent={
                    id : e.parent.id,
                    SCHEMA : e.parent.SCHEMA.module.code+"."+e.parent.SCHEMA.code
                };
            }
            // if (level < 2) {
                for (var x of e.tags||[])
                    r.tags.push(rec(x,level+1));
                for (var x of e.children||[])
                    r.children.push(rec(x,level+1));
            // }
        } else if (e instanceof db.web2print.print_tag) {
            r =
            {
                id : e.id,
                SCHEMA : e.SCHEMA.module.code+"."+e.SCHEMA.code,
                code : e.code,
                NAME : misc.OBJSTR(e)
            };
        }
        return r;
    }
    var res=[];
    if (id) {
        var cat = db.web2print.tag_category.byId(id);
        if (cat){

        }
            for (var e of (cat.children || []))
                res.push(rec(e,0));
    } else {
        for (var cat of db.web2print.tag_category.SELECT("parent is null"))
            res.push(rec(cat,0));
    }
    return res;
}