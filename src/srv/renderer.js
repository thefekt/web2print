var lastUpdateTimes = {};

exports.renderTemplate = function(tmpl,data) 
{
    if (!tmpl.document)
        return { error : "document not existing or corrupted!"};

    var key =  "rndr-"+tmpl.document.id;
    __vr.Exec.callVSC("java.util.executeJSWithTempDirLock",key,function() 
    {
        // NEEDS REFRESH?
        var doRefresh=(!lastUpdateTimes[key] || lastUpdateTimes[key] != tmpl.update_time);
        if (doRefresh)
        {
            com.planvision.visionr.server6.mod.web.ScribusService.forceStop(key);
            console.warn(">>> UPDATING "+key+" | "+tmpl.update_time+" | "+tmpl.code);
            lastUpdateTimes[key]=tmpl.update_time;
            __vr.Exec.callVSC("java.util.extractInTempDirectory",key,tmpl.document);
        }
        var toReplace = {};
        try 
        {
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
                    if (d instanceof db.documents.file)
                        __vr.Exec.callVSC("java.util.copyInTempDirectory","rndr-"+tmpl.document.id,d,e.code);
                    else
                        console.warn("render.js : document is not a file!");
                } else if (e instanceof db.web2print.qrcode_content ) {
                    var d = data[e.code]||e.initial_value;
                    if (d) 
                        __vr.Exec.callVSC("java.util.generateQRInTempDirectory","rndr-"+tmpl.document.id,d,e.code);
                } 
            }
        } finally {
            //__vr.Exec.callVSC("java.util.cleanupTempDirectory");
        }
        console.warn("DONEEEEEEEEEEEEEEEEEE!");
        var res = com.planvision.visionr.server6.mod.web.ScribusService.convert(key,OBJSTR(tmpl),encodeURIComponent(JSON.stringify(toReplace)));
    });
}