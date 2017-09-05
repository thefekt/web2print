#!/usr/bin/env vr

for (var e of db.web2print.print_template.SELECT()) {
    e.preview_key=undefined;
    var oldPd = e.preview_document;
    if (oldPd) {
        e.preview_document=null;
        oldPd.delete();
    }
}
console.log("RESET-PRINT done!");