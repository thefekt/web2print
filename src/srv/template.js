exports.createTemplate = function(code,document) {
    var err = db.web2print.print_template.byCode(code) ? require("server/transaction").checkDeleteObjectsConstraints("web2print.print_template","code=:CODE",{CODE:code}) : undefined;
    if (err &&
        (
            (err.access && err.access.length) ||
            (err.locked && err.locked.length) ||
            err.dependencyError
        )
    ) {
        return "Unable to delete existing template";
    }
    var tmpl = new db.web2print.print_template();
    tmpl.document=document;
    tmpl.code=code;
    tmpl.commit();
    document.parent= JSCORE.Exec.callVSC("doc.misc.getUserUploadFolder");
    document.commit();
    return tmpl;
};

exports.deleteTemplate = function(template) {
    if (!template.ACCESS.deletable)
        return;
    template.delete();
};

exports.updateTemplate = function(code,document)
{
    var tmpl = db.web2print.print_template.byCode(code);
    if (!tmpl) return "not found";
    if (!tmpl.ACCESS.updatable || !tmpl.SCHEMA.ACCESS.updatable)
        return "access denied";
    tmpl.document && !tmpl.document.DELETED && tmpl.document.delete();
    tmpl.document=document;
    tmpl.code=code;
    tmpl.commit();
    document.parent= JSCORE.Exec.callVSC("doc.misc.getUserUploadFolder");
    document.commit();
    return tmpl;
};
