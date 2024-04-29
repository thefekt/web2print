const misc = require("server/misc");
const renderer = require("./renderer");
const docapi = require("server/documents/document");

exports.initTemplateContentsUploaded = function(doc) {
	if (!session.isAdmin && !session.isUserGroup("customer:admin")) return;

	// TODO : fix without reset | only if impoorting is cancelled then error on reimport
	renderer.resetTempDir(); 

	return renderer.initTemplateContents({code:doc.code.substring(0,doc.code.lastIndexOf('.')),document:doc});
};
exports.createTemplate = function(code,document) {
	
	if (!session.isAdmin && !session.isUserGroup("customer:admin")) return;
    var err = db.web2print.print_template.byCode(code) ? require("server/form").tryDeleteObjects("web2print.print_template","code=:CODE",{CODE:code}) : undefined;
    if (err &&
        (
            (err.access && err.access.length) ||
            err.dependencyError
        )
    ) {
        return "Unable to delete existing template";
    }
    var tmpl = new db.web2print.print_template();
    tmpl.document=document;
    tmpl.code=code;
    tmpl.commit();
    
    var dir = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${tmpl.uuid}`})[0];
    if (!dir) throw `can not find document.folder '/web2print/${tmpl.uuid}'` 
    document.parent=dir;
    exports.applyDefaultAccess(tmpl);

    document.commit();
    return tmpl;
};
exports.deleteTemplate = function(template) {
	if (!session.isAdmin && !session.isUserGroup("customer:admin")) return;
    if (!template.ACCESS.deletable)
        return;
    template.delete();
};
exports.updateTemplate = function(code,document) {
	if (!session.isAdmin && !session.isUserGroup("customer:admin")) return;
    var tmpl = db.web2print.print_template.byCode(code);
    if (!tmpl) return "not found";
    if (!tmpl.ACCESS.updatable || !tmpl.SCHEMA.ACCESS.updatable)
        return "access denied";
    tmpl.document && !tmpl.document.DELETED && tmpl.document.delete();
    tmpl.document=document;
    tmpl.code=code;
    tmpl.commit();
  
    var dir = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${tmpl.uuid}`})[0];
    if (!dir) throw `can not find document.folder '/web2print/${tmpl.uuid}'` 
    document.parent=dir;
    exports.applyDefaultAccess(tmpl);

    document.commit();
    return tmpl;
};

exports.applyDefaultAccess = function(tmpl) {
	if (!session.isAdmin && !session.isUserGroup("customer:admin")) return;
	var everyone = db.core.user_role.byCode("everyone");
	var admin = db.core.user_role.byCode("customer:admin");

    tmpl.access_read=everyone;
	tmpl.access_update=admin;
	tmpl.access_copy=admin;
	tmpl.access_update=admin;
	for (var e of tmpl.contents||[]) {
		e.access_read=everyone;
		e.access_update=admin;
		e.access_delete=admin;
		e.access_copy=admin;
	} 
}
exports.getInitialImageRegion = function(document,proportion) {
    if (!document)
        return;
    var dims = JSCORE.Exec.callVSC("java.util.getDocumentDimensions",document);
    if (!dims || !dims[0] || !dims[1])
        return;
    var width,height;
    if (proportion > 0) {
        var dw1 = dims[0],dh1 = dims[0]/proportion,dw2 = dims[1]*proportion,dh2 = dims[1];
        if (dh1 <= dims[1]) {
            width=dw1;
            height=dh1;
        } else {
            width=dw2;
            height=dh2;
        }
    } else {
        width=dims[0];
        height=dims[1];
    }
    // max rendered image dimension (NOT pdf)
    if (width > height) {
        if (width > 2048) {
            var t = width/height;
            width=2048;
            height=2048/t;
        }
    } else {
        if (height > 2048) {
            var t = height/width;
            height=2048;
            width=2048/t;
        }
    }
    var res = {
        x : Math.round(dims[0]/2-width/2),y : Math.round(dims[1]/2-height/2),
        w : Math.round(width),h : Math.round(height),
        dw : Math.round(width),dh : Math.round(height),
        p : 0,z : 0,f : document.uuid,
    };
    var ext = docapi.imageFormatSupportsTransparency(document.extension?.code||'PNG') ? "png" : "jpg";
    res.url="/tmp/documents/"+res.f+".uuid."+ext+"?operation=resizeImage"+
        "&width=480"+
        "&f="+encodeURIComponent(res.f);
    return res;
}
//------------------------------------------------------------------------------
// custom gui upload as private
//------------------------------------------------------------------------------
exports.uploadUserDocument = function(tmpl,uploads) {
	var docs=db.documents.document.SELECT({where:"uuid IN (:UUIDS)",orderBy:'id',UUIDS:uploads});
	var dir = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${tmpl.uuid}/private`})[0];
	if (!dir) throw `can not find document.folder '/web2print/${tmpl.uuid}/private'` 
	for (var doc of docs) {
		// SETUP UPLOAD DOCUMENT
		doc.parent=dir;
		if (session.loggedIn)	// persisted over session only for logged users
			doc.access_read_session=undefined;
	}
	return docs[0];
};

exports.deleteTemplateDocuments = function(tmpl,documents) {
	if (!tmpl) return;
	var dir = db.documents.folder.SELECT({where:"path=:PATH",PATH:`/web2print/${tmpl.uuid}/private`})[0];
	if (!dir) throw `can not find document.folder '/web2print/${tmpl.uuid}/private'` 
	var todel=[];
    for (var e of documents)  {
		if (!(e instanceof db.documents.document)) throw "not a document";
		if (e.parent?.id == dir.id || session.isAdmin || session.loggedIn && e.ACCESS.owner) {
			if (!e.ACCESS.deletable) 
				return `${misc.MSG("ACCESS_DENIED")} : ${e}`;
			todel.push(e);
		} else 
			return `${misc.MSG("ACCESS_DENIED")} : ${e}`;
    }
    for (var e of todel) {
		e.delete();
		e.commit();
	}
};

//------------------------------------------------------------------------------
exports.getTemplateDetails = function (code) {
    var tpl = db.web2print.print_template.byCode(code);
    if (!tpl) return {path:[],name: 'getTemplateDetails: error - no template found'};
    var d1 = getContentsData(tpl);
    var mp = misc.MSG("INFO_PAGE");
	var catById = {};
	var cats = [];
	d1.sort(function(a,b){
		var sa = a.dest_page||1;
		var sb = b.dest_page||1;
		return sa-sb;
	});
    for (var e of d1) {
		var k;
		var p = (e.dest_page||1);
		if (!e.category) k = -p;
		else k = e.category.id;
		var t = catById[k];
		if (!t) 
			cats.push(t=catById[k]={minp:p,maxp:p,name:p<0 ? undefined : misc.OBJSTR(e.category),entries:[e]});
		else {
			if (p < t.minp) t.minp=p;
			if (p > t.maxp) t.maxp=p;				
			t.entries.push(e);
		}
	}
	for (var e of cats) {
		var sfx = e.minp == e.maxp ? e.minp : `${e.minp} - ${e.maxp}`;
		if (e.name) 
			e.sfx = sfx; 
		else
			e.name = misc.MSG("INFO_PAGE")+' '+sfx;
	}
    return {
        name : misc.OBJSTR(tpl),
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
        contents : cats
    };
}
//------------------------------------------------------------------------------
function getContentsData(tpl) {
	var dbw2p = db.web2print;
	
    if (typeof tpl == "number")
        tpl=dbw2p.print_template.byId(tpl);
    if (!tpl || !tpl.contents)
        return [];
    function fixName(name) {
        var i = name.indexOf(".");
        if (i > 0) return name.substring(0,i);
        return name;
    }
    var codes = [];
    var lang = session.lang.code;
    for (var e of tpl.contents) if (e instanceof dbw2p.qrcode_content || e instanceof dbw2p.image_content) {
		var c = e.code;
		codes.push(`${c}.${lang}.png`);
		codes.push(`${c}.${lang}.pdf`);
		codes.push(`${c}.${lang}.jpg`);
		codes.push(`${c}.${lang}.jpeg`);
	}
	var c2d = {}
	for (var d of db.documents.document.SELECT({where:"code = ANY (:CODES) AND parent.path = :PATH",PATH:"/web2print/"+tpl.uuid+"/defaults",CODES:codes})) {
		var c = d.code;
		if (c.endsWith(".jpeg")) c = c.substring(0,c.length-6-lang.length);
		else c=c.substring(0,c.length-5-lang.length);
		c2d[c]=d;		
	}
    
    var res=[];
    for (var e of tpl.contents) {
        if (e instanceof dbw2p.varchar_content) {
            res.push({
                object : e,
                code : e.code,
                category : e.category,
                NAME : misc.OBJSTR(e),
                type : 'varchar',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.text_content) {
            res.push({
                object : e,
                code : e.code,
				category : e.category,
                NAME : misc.OBJSTR(e),
                type : 'text',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.date_content) {
            res.push({
                object : e,
                code : e.code,
				category : e.category,
                NAME : misc.OBJSTR(e),
                type : 'date',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.time_content) {
            res.push({
                object : e,
                code : e.code,
				category : e.category,
                NAME : misc.OBJSTR(e),
                type : 'time',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.datetime_content) {
            res.push({
                object : e,
				category : e.category,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'datetime',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.integer_content) {
            res.push({
                object : e,
				category : e.category,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'integer',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.double_content) {
            res.push({
                object : e,
				category : e.category,
                code : e.code,
                NAME : misc.OBJSTR(e),
                type : 'double',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.image_content) {
			var dd = c2d[e.code];
            res.push({
                object : e,
				category : e.category,
                code : e.code,
                NAME : fixName(misc.OBJSTR(e)),
                type : 'image',
                dest_page : e.dest_page,
                proportion : e.proportion,
                placeholder : { document : dd , region : exports.getInitialImageRegion(dd,e.proportion) }
            });
        } else if (e instanceof dbw2p.table_content && e.table_data) {
            res.push({
                object : e,
				category : e.category,
                code : e.code,
                NAME : fixName(misc.OBJSTR(e)),
                type : 'table',
                dest_page : e.dest_page,
                placeholder : JSON.parse(e.table_data)
            });
        } else if (e instanceof dbw2p.qrcode_content) {
            res.push({
                object : e,
                NAME : fixName(misc.OBJSTR(e)),
				category : e.category,
                code : e.code,
                type : 'qrcode',
                dest_page : e.dest_page,
                placeholder : e.initial_value
            });
        } else if (e instanceof dbw2p.indexed_color_content) {
            var colors = e.available_colors;
            if (colors) {
                var arr=[];
                for (var i=0;i<colors.length;i++) {
                    var t = colors[i];
                    if (t) arr.push({code:t.code,rgb:t.value_rgb,cmyk:t.value_cmyk,name:t+''});
                }
                if (arr.length)
                    colors=arr;
                else
                    colors=undefined;
            }
            var eiv = e.initial_value;
            res.push({
                object : e,
                NAME : misc.OBJSTR(e),
				category : e.category,
                code : e.code,
                type : 'color',
                dest_page : e.dest_page,
                placeholder : eiv ? {code:eiv.code,rgb:eiv.value_rgb,cmyk:eiv.value_cmyk,name:eiv+''} : undefined,
                colors: colors,
                region : e.region ? JSON.parse(e.region).region : undefined
            });
        }
    }
    return res;
}

