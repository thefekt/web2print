var lastUpdateTimes = {};

exports.renderTemplate = function(tmpl,data) 
{
    if (!tmpl.document)
        return { error : "document not existing or corrupted!"};

    var key =  "rndr-"+tmpl.document.id;
    var result = undefined;
    __vr.Exec.callVSC("java.util.executeJSWithTempDirLock",key,function() 
    {
        // NEEDS REFRESH?
        var upd = tmpl.update_time ? tmpl.update_time.getTime() : 0;
        var doRefresh=lastUpdateTimes[key] != upd;
        if (doRefresh)
        {
            com.planvision.visionr.server6.mod.web.ScribusService.forceStop(key);
            console.warn(">>> UPDATING "+key+" | "+upd+" | "+tmpl.code);
            __vr.Exec.callVSC("java.util.extractInTempDirectory",key,tmpl.document);
            lastUpdateTimes[key]=upd;
        }
        var toReplace = exports.getTemplateJSON(tmpl,data,false);
        result = com.planvision.visionr.server6.mod.web.ScribusService.convert(key,tmpl+"",encodeURIComponent(JSON.stringify(toReplace)));
        if (!result) {
            // TODO TODO TODO 
            // BUG stelf? first convert error ? command not recognised?
            log.warn("STELF? TODO ? Scribus generator returned FIRST EMPTY result : "+tmpl.code);
            result = com.planvision.visionr.server6.mod.web.ScribusService.convert(key,tmpl+"",encodeURIComponent(JSON.stringify(toReplace)));
            if (!result)
                log.error("!!!! Scribus generator returned EMPTY result : "+tmpl.code);
        }
    });
    return result;
}

exports.getTemplateJSON = function(tmpl,data,doNotRender) {
    var toReplace = {};
    for (var e of tmpl.contents) 
    {
        if (e instanceof db.web2print.varchar_content ) {
            var d = data[e.code]||e.initial_value;
            if (!d) d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.text_content ) {
            var d = data[e.code]||e.initial_value;
            if (!d) d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.date_content ) {
            var d = data[e.code]||e.initial_value;
            if (d)
                d=db.core.output_format.byCode("default_output_format_date").get_as_string(data[e.code]);
            else 
                d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.datetime_content ) {
            var d = data[e.code]||e.initial_value;
            if (d)
                d=db.core.output_format.byCode("default_output_format_datetime").get_as_string(data[e.code]);
            else 
                d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.time_content ) {
            var d = data[e.code]||e.initial_value;
            if (d)
                d=db.core.output_format.byCode("default_output_format_time").get_as_string(data[e.code]);
            else 
                d="";
            toReplace[e.code]=d;
        } else if (e instanceof db.web2print.image_content) {
            var d = data[e.code]||e.initial_value;
                if (d instanceof db.documents.file) {
                    if (!doNotRender) {
                        __vr.Exec.callVSC("java.util.copyInTempDirectory","rndr-"+tmpl.document.id,d,e.code);
                    } else {
                        // JUST A KEY
                        toReplace[e.code]=d.update_time||d.insert_time||0;
                    }
                }
                else
                    console.warn("render.js : document is not a file!");
        } else if (e instanceof db.web2print.qrcode_content ) {
            var d = data[e.code]||e.initial_value;
            if (d) 
            {
                if (!doNotRender) {
                    __vr.Exec.callVSC("java.util.generateQRInTempDirectory","rndr-"+tmpl.document.id,d,e.code);
                } else {
                    // JUST A KEY
                    toReplace[e.code]=d;
                }
            }
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
    tmpl.preview_document=exports.renderTemplate(tmpl,{});
    if (tmpl.preview_document == null)
        return;
    var parent = db.documents.folder.byCode("/");
    var rparent = parent.children["print_cache"];
    if (rparent == null) {
        rparent = new db.documents.folder();
        rparent.code="print_cache";
        rparent.parent=parent;
    }
    parent=rparent; 
    //-------------------------------------------
    tmpl.preview_document.parent=parent;
    console.warn(key);
    console.warn(" >> PREVIEW UPDATED : "+tmpl+" | "+key+" | "+tmpl.preview_document);
}