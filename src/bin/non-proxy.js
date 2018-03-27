
var port = 3333;

var proxy = require('http-proxy-middleware');
var express = require('express');

var app = express();
var server = require('http').Server(app);

server.listen(port, () => {
  console.log('Express listening to port '+port);
});

var wsProxy = proxy('ws://localhost:8585', {changeOrigin:true});

server.on("upgrade",function(req, socket, head) {
 if (!req.url.startsWith("/wsapi/")) {
  wsProxy.upgrade(req,socket,head);
 }
});

app.use(proxy('/wordpress/', {
  target: 'http://localhost/',
  // changeOrigin: true,
  // autoRewrite: true,
  cookieDomainRewrite:true } ));

app.use(proxy({
  target: `http://localhost:4300/`,
  cookieDomainRewrite:true,
} ));
