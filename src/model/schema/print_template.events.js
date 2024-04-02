/* server events (backend js) */

exports.onInsertobject = function() {
	var parent = db.documents.folder.SELECT("path='/' AND parent IS NULL")[0];
	if (!parent) return;
	var everyone = db.core.user_role.byCode("everyone");
	var admin = db.core.user_role.byCode("customer:admin");
	function createDir(parent,code) {
		var rparent = db.documents.folder.SELECT("code=:CODE AND parent = :ID",{CODE:code,ID:parent.id,ACCESSDISABLED:true})[0];
		if (!rparent) {
			rparent = new db.documents.folder();
			rparent.code=code;
			rparent.parent=parent;
			rparent.access_read=everyone;
			rparent.access_update=admin;
			rparent.access_delete=admin;
			rparent.access_copy=admin;
			rparent.commit();
		}
		return rparent;
	} 
	var p1 = createDir(parent,'web2print');
	var p2 = createDir(p1,this.uuid);
	createDir(p2,"public");
	createDir(p2,"private");
	createDir(p2,"defaults");	
}

/* server function, used only as source */
exports.onDocumentChange=function() {

	if (!this.document) {
	    if (this.preview_key)
	        this.preview_key=null;
	    if (this.preview_document)
	        this.preview_document=null;
		if (this.OLD.document) {
			require("web2print/renderer").forceStop(this.OLD);
		}
	    return;
	}
	//-------------------------------------------------------------------
	// CLEANUP defaults dir
	//-------------------------------------------------------------------
	if (!this.INSERTED) {
		// skip it new created template object (skip delete initial values on create)
		var nparent = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${this.uuid}/defaults`,ACCESSDISABLED:true})[0];
		if (!nparent) throw `can not find parent '/web2print/${this.uuid}/defaults'`;
		// cleanup dir
		for (var e of nparent.children||[]) {
			e.delete();
			e.commit();
		}	
	}
	//-------------------------------------------------------------------
	require("web2print/renderer").initTemplateContents(this);
	require("web2print/renderer").checkPreview(this);
	require("web2print/renderer").forceStop(this);
	var everyone = db.core.user_role.byCode("everyone");
	var admin = db.core.user_role.byCode("customer:admin");

	var nparent = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${this.uuid}/defaults`,ACCESSDISABLED:true})[0];
	if (!nparent) throw "can not find parent `/web2print/${this.uuid}/defaults`";

	for (var e of this.contents||[]) if  (e instanceof db.web2print.image_content && e.initial_value) {
		e.initial_value.access_read=everyone;
		e.initial_value.access_update=admin;
		e.initial_value.access_delete=admin;
		e.initial_value.access_copy=admin;
		e.initial_value.parent=nparent;
	}
}

exports.onContentsChange = function() {
	if (!this.document) {
	    if (this.preview_key)
	        this.preview_key=null;
	    if (this.preview_document)
	        this.preview_document=null;
	    return;
	}
	require("web2print/renderer").checkPreview(this);
	require("web2print/renderer").forceStop(this);	
}

exports.onDeleteObject = function() {
	function getFolder() {
	    var parent = db.documents.folder.SELECT("path='/' AND parent IS NULL")[0];
	    if (!parent) return;
	    parent = db.documents.folder.SELECT("code=:CODE AND parent = :ID",{CODE:'web2print',ID:parent.id,limit:1,ACCESSDISABLED:true})[0];
	    if (!parent) return;
	    return db.documents.folder.SELECT("code=:CODE AND parent = :ID",{CODE:this.OLD.uuid,ID:parent.id,limit:1,ACCESSDISABLED:true})[0];
	};
	var folder = getFolder.apply(this);
	function checkAccessDelete(e) {
		if (!(e instanceof db.documents.document_node) || !e.ACCESS.readable || !e.ACCESS.updatable || !e.ACCESS.deletable || !e.SCHEMA.ACCESS.readable || !e.SCHEMA.ACCESS.updatable || !e.SCHEMA.ACCESS.deletable)
    		throw `Access denied delete for ${e.KEY}`;
	}
	function rec(e) {
	    if (!e || e.DELETED) return;
	    for (var c of e.children||[]) 
	        rec(c);
	    checkAccessDelete(e);
	    e.delete();
	}
	rec(folder);
	//-----------------------------------------------------------------------------
	for (var e of this.OLD.contents || []) {
	    if(!e.DELETED) {
	        e.delete();
	        e.commit();
	        }
	}
	var doc = this.OLD.document;
	if (doc && !doc.DELETED) {
	    var a = db.web2print.print_template.SELECT("document = :DOC AND id <> :ID",{
	        ID : this.id,
	        DOC : doc.id,
	        limit : 1,
	        ACCESSDISABLED:true
	    });
	    if (!a.length) {
		    checkAccessDelete(doc);
		    e.delete(doc);
	    }
	}
}