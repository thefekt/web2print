// @allowEveryone

var lastUpdateTimes = {};
var misc = require("server/misc");
var service = VSERVER.mod.web.ScribusService;

function syncTemp(tmpl) {
    var key =  "rndr-"+tmpl.document.id;
    var upd = tmpl.update_time ? tmpl.update_time.getTime() : 0;
    var doRefresh=lastUpdateTimes[key] != upd;
    if (doRefresh)
    {
        service.forceStop(key);
        console.warn(`render.js: updating -> service key [${key}]\n\t update time [${upd}]\n\t template [${tmpl.code}]\n\t file [${tmpl.document}]`);
        console.warn(["java.util.extractInTempDirectory",key,tmpl.document].join(", "));
        JSCORE.Exec.callVSC("java.util.extractInTempDirectory",key,tmpl.document,{});
        console.info(`render.js: content extracted in temp.`);
        lastUpdateTimes[key]=upd;
    }
}
exports.initTemplateContents = function(tmpl) {
    var key =  "rndr-"+tmpl.document.id;
    JSCORE.Exec.callVSC("java.util.executeJSWithTempDirLock",key,function()
    {
        syncTemp(tmpl);
        var contents = service.getAvailableContents(key);
        for (var e of tmpl.contents || []) {
            e.delete();
            e.commit();
        }
        var arr=[];
        for (var e of contents) {
            switch (e.type) {
                case "varchar" :
                    var c = new db.web2print.varchar_content();
                    c.code=e.code;
                    c.initial_value=e.code;
                    c.template=tmpl;
                    c.commit();
                    arr.push(c);
                    break;
                case "image" :
                    var c = new db.web2print.image_content();
                    c.code=e.code;
                    c.template=tmpl;
                    c.commit();
                    arr.push(c);
                    break;

                }
            }
        if (arr.length)
            tmpl.contents=arr;
        tmpl.commit();
        log.warn("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! "+JSON.stringify(tmpl.contents,null,4));
    });
};

exports.renderTemplate = function(tmpl,data)
{
    console.info(`render.js: render ${tmpl.document.code} with ${JSON.stringify(data)} `);

    if (!tmpl.document) {
      return { error : "document not existing or corrupted!"};
      console.error("document not existing or corrupted!");
    }

    var key =  "rndr-"+tmpl.document.id;
    var result = undefined;
    JSCORE.Exec.callVSC("java.util.executeJSWithTempDirLock",key,function()
    {
        syncTemp(tmpl);
        var toReplace = exports.getTemplateJSON(tmpl,data,false);
        result = service.convert(key,tmpl+"",encodeURIComponent(JSON.stringify(toReplace)));
        if (!result) {
            console.warn("render.js: Scribus generator returned FIRST EMPTY result : "+tmpl.code);
            result = service.convert(key,tmpl+"",encodeURIComponent(JSON.stringify(toReplace)));
            if (!result)
                log.error("!!!! Scribus generator returned EMPTY result : "+tmpl.code);
        }
    });
    return result;
}

exports.getTemplateJSON = function(tmpl,data,doNotRender) {
    var toReplace = {};

    for (var e of tmpl.contents||[])
    {
        if (e instanceof db.web2print.varchar_content ) {
            var d = data[e.code] !== undefined ? data[e.code] : e.initial_value;
            if (!d) d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.text_content ) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (!d) d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.date_content ) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (d)
                d=db.core.output_format.byCode("default_output_format_date").get_as_string(data[e.code]);
            else
                d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.datetime_content ) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (d)
                d=db.core.output_format.byCode("default_output_format_datetime").get_as_string(data[e.code]);
            else
                d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.time_content ) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (d)
                d=db.core.output_format.byCode("default_output_format_time").get_as_string(data[e.code]);
            else
                d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.image_content) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (d instanceof db.documents.file) {
                if (!doNotRender) {
                    JSCORE.Exec.callVSC("java.util.copyInTempDirectory","rndr-"+tmpl.document.id, d, e.code);
                } else {
                    // JUST A KEY
                    toReplace[e.code]=d.update_time||d.insert_time||0;
                }
            }
            else {
              console.warn("render.js : document [" + e.code + "] is not a db.documents.file instance!");
            }
          } else if (e instanceof db.web2print.qrcode_content ) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (d)
            {
                if (!doNotRender) {
                    JSCORE.Exec.callVSC("java.util.generateQRInTempDirectory","rndr-"+tmpl.document.id,d,e.code);
                } else {
                    // JUST A KEY
                    toReplace[e.code]=d;
                }
            }
        } else if (e instanceof db.web2print.indexed_color_content ) {
            var d = data[e.code]!== undefined ? data[e.code] : e.initial_value;
            if (!d) d="";
            toReplace[e.code]=d;
        }
    }
    return toReplace;
}

exports.checkPreview = function(tmpl) {
    var data = exports.getTemplateJSON(tmpl,{},true);
    var key = JSON.stringify(data);
    if (tmpl.preview_document && !tmpl.preview_document.DELETED && key == tmpl.preview_key)
        return;
    tmpl.preview_key=key;
    var oldPd = tmpl.preview_document;
    if (oldPd) {
        tmpl.preview_document=null;
        oldPd.delete();
    }
    tmpl.preview_document=exports.renderTemplate(tmpl,{});
    if (tmpl.preview_document == null)
        return;

//        var parent = db.documents.folder.byCode("/");
//    log.warn('parent ' + JSON.stringify(parent,null,4));
    var rparent = db.documents.folder.byCode('print_cache');
    //log.warn('rparent ' + rparent);

    if (rparent == null) {
        rparent = new db.documents.folder();
        rparent.code="print_cache";
        rparent.parent=parent;
    }
    var parent=rparent;

    //-------------------------------------------
    tmpl.preview_document.parent=parent;
    try {
        var dim =JSCORE.Exec.callVSC("java.util.getPDFDimensions",tmpl.preview_document.uuid,0);
        var width = dim[0]*297/847;
        var height = dim[1]*297/847;
        tmpl.width_mm=width;
        tmpl.height_mm=height;
    } catch (e) {
        console.error(e);
    }

    console.warn(" >> PREVIEW UPDATED : "+tmpl+" | "+key+" | "+tmpl.preview_document);
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

exports.getTemplateDetails = function (id) {
    var tpl;

    if (typeof id == 'string') {
        tpl = db.web2print.print_template.byCode(id);
    } else {
         tpl = db.web2print.print_template.byId(Number(id));
    }

    if (!tpl) return {path:[],NAME: 'getTemplateDetails: error - no template found'};
    var d1 = exports.getContentsData(tpl);

    return {
        object :
        {
            NAME : misc.OBJSTR(tpl)+" ("+misc.formatDouble(tpl.width_mm)+"x"+misc.formatDouble(tpl.height_mm)+" mm)",
            width : tpl.width_mm,
            height : tpl.height_mm,
            id : tpl.id,
            uuid: tpl.uuid,
            SCHEMA : tpl.SCHEMA.module.code+"."+tpl.SCHEMA.code,
            preview_document : tpl.preview_document ? {
              id : tpl.preview_document.id,
              SCHEMA : tpl.preview_document.SCHEMA.KEY,
              uuid : tpl.preview_document.uuid
            } : undefined,
            contents : d1,
            defaultData : getContentsDefault(d1)
        }
    };
}

exports.getContentsData = function(tpl) {
    if (typeof tpl == "number")
        tpl=db.web2print.print_template.byId(tpl);
    if (!tpl || !tpl.contents)
        return [];
    var res=[];
    for (var e of tpl.contents) {
        if (e instanceof db.web2print.varchar_content) {
            res.push({
                object : e,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'varchar',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.text_content) {
            res.push({
                object : e,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'text',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.date_content) {
            res.push({
                object : e,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'date',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.time_content) {
            res.push({
                object : e,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'time',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.datetime_content) {
            res.push({
                object : e,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'datetime',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.image_content) {
            res.push({
                object : e,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'image',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.qrcode_content) {
            res.push({
                object : e,
                NAME : misc.OBJSTR(e),
                code : e.code,
                type : 'qrcode',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof db.web2print.indexed_color_content) {
            res.push({
                object : e,
                NAME : misc.OBJSTR(e),
                code : e.code,
                type : 'color',
                dest_page : e.dest_page,
                placeholder : e.initial_value,
                colors: e.available_colors
            });
        }
    }
    return res;
}

// NOT EXPORTED
function getContentsDefault(t) {
    var res = [];
    for (var i=0;i<t.length;i++)
        res.push(t[i].placeholder);
    return res;
}



var _cachedir;
function getCacheDir()
{
    if (_cachedir)
        return _cachedir;
    var parent = db.documents.folder.SELECT("path='/visionr/print_cache'")[0];
    if (!parent) {
        log.error("UNABLE TO FIND PRINT CACHE FOLDER!");
        throw new Exception("UNABLE TO FIND PRINT CACHE FOLDER!");
    }
    return _cachedir=parent;
}
