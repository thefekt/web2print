
var lastUpdateTimes = {};

exports.renderTemplate = function(tmpl,data) 
{
    if (!tmpl.document)
        return { error : "document not existing or corrupted!"};

    var key =  "rndr-"+tmpl.document.id;
    __vr.Exec.callVSC("java.util.executeJSWithTempDirLock",key,function() 
    {
        // NEEDS REFRESH?
        if (!lastUpdateTimes[key] || lastUpdateTimes[key] != tmpl.update_time) 
        {
            console.warn(">>> UPDATING "+key+" | "+tmpl.update_time+" | "+tmpl.code);
            lastUpdateTimes[key]=tmpl.update_time;
            try 
            {
                __vr.Exec.callVSC("java.util.copyInTempDirectory",key,tmpl.document,"template.sla");

                var toReplace = [];

                for (var e of tmpl.contents) 
                {
                    if (e instanceof db.web2print.varchar_content ) {
                        var d = data[e.code]||e.initial_value;
                        if (!d) d="";
                        toReplace.push({from : e.code, to : d || e.initial_value});
                    } else if (e instanceof db.web2print.text_content ) {
                        var d = data[e.code]||e.initial_value;
                        if (!d) d="";
                        toReplace.push({from : e.code, to : d || e.initial_value});
                    } else if (e instanceof db.web2print.date_content ) {
                        var d = data[e.code]||e.initial_value;
                        if (d)
                            d=db.core.output_format.byCode("default_output_format_date").get_as_string(data[e.code]);
                        else 
                            d="";
                        toReplace.push({from : e.code, to : d || e.initial_value});
                    } else if (e instanceof db.web2print.datetime_content ) {
                        var d = data[e.code]||e.initial_value;
                        if (d)
                            d=db.core.output_format.byCode("default_output_format_datetime").get_as_string(data[e.code]);
                        else 
                            d="";
                        toReplace.push({from : e.code, to : d || e.initial_value});
                    } else if (e instanceof db.web2print.time_content ) {
                        var d = data[e.code]||e.initial_value;
                        if (d)
                            d=db.core.output_format.byCode("default_output_format_time").get_as_string(data[e.code]);
                        else 
                            d="";
                        toReplace.push({from : e.code, to : d || e.initial_value});
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
                    console.warn("DONEEEEEEEEEEEEEEEEEE!");
                }
            } finally {
                //__vr.Exec.callVSC("java.util.cleanupTempDirectory");
            }
        }
    });
}

