// يحوّل صفحة الشريط لصيغة تشتغل على المتصفحات القديمة (أندرويد 5 / WebView 39)
// الاستخدام: node legacy.js in.html out.html
const fs = require('fs'), path = require('path');
const babel = require('@babel/core');
const [,, inp, out] = process.argv;
let html = fs.readFileSync(inp, 'utf8');

// 1) متغيرات CSS الثابتة → قيمها
const vars = {};
html.replace(/(--[a-zA-Z0-9-]+)\s*:\s*([^;}]+)[;}]/g, (m, k, v) => { if (!(k in vars)) vars[k] = v.trim(); return m; });
html = html.replace(/var\((--[a-zA-Z0-9-]+)(?:\s*,\s*([^)]+))?\)/g, (m, k, fb) => vars[k] || (fb ? fb.trim() : m));

// 2) تحويل السكربتات الحديثة لـ ES5
const modern = /=>|\bconst\b|\blet\b|\basync\b|`|\bclass\b/;
html = html.replace(/<script>([\s\S]*?)<\/script>/g, (m, code) => {
  if (!modern.test(code)) return m;
  const r = babel.transformSync(code, {
    babelrc: false, configFile: false, sourceType: 'script', compact: false, comments: false,
    presets: [[require.resolve('@babel/preset-env'), { targets: { chrome: '37' }, modules: false }]],
  });
  return '<script>' + r.code.replace(/<\/script/gi, '<\\/script') + '</script>';
});

// WebView 39: document.fonts.ready كانت دالة مو Promise
html = html.split('document.fonts.ready.then(').join("(typeof document.fonts.ready==='function'?document.fonts.ready():document.fonts.ready).then(");

// 3) مكملات: regenerator + fetch + padStart/padEnd + Object.assign
const nm = path.join(__dirname, 'node_modules');
const poly = [
  fs.readFileSync(path.join(nm, 'regenerator-runtime/runtime.js'), 'utf8'),
  fs.readFileSync(path.join(nm, 'whatwg-fetch/dist/fetch.umd.js'), 'utf8'),
  `if(!String.prototype.padStart){String.prototype.padStart=function(n,s){var t=String(this);s=s===undefined?' ':String(s);while(t.length<n)t=s+t;return t.slice(t.length-Math.max(n,String(this).length));};}
if(!String.prototype.padEnd){String.prototype.padEnd=function(n,s){var t=String(this);s=s===undefined?' ':String(s);while(t.length<n)t+=s;return t.slice(0,Math.max(n,String(this).length));};}
if(!Object.assign){Object.assign=function(t){for(var i=1;i<arguments.length;i++){var s=arguments[i];if(s)for(var k in s)if(Object.prototype.hasOwnProperty.call(s,k))t[k]=s[k];}return t;};}
if(!Array.prototype.includes){Array.prototype.includes=function(x){return this.indexOf(x)>=0;};}
if(!String.prototype.includes){String.prototype.includes=function(x,p){return this.indexOf(x,p||0)>=0;};}
if(!String.prototype.startsWith){String.prototype.startsWith=function(x,p){p=p||0;return this.substr(p,x.length)===x;};}
if(!String.prototype.endsWith){String.prototype.endsWith=function(x){return this.slice(-x.length)===x;};}
if(!Array.from){Array.from=function(a,f){var r=[];for(var i=0;i<a.length;i++)r.push(f?f(a[i],i):a[i]);return r;};}
if(!Array.prototype.find){Array.prototype.find=function(f){for(var i=0;i<this.length;i++)if(f(this[i],i,this))return this[i];};}
if(!Array.prototype.findIndex){Array.prototype.findIndex=function(f){for(var i=0;i<this.length;i++)if(f(this[i],i,this))return i;return -1;};}
if(!Object.entries){Object.entries=function(o){return Object.keys(o).map(function(k){return [k,o[k]];});};}
if(!Object.values){Object.values=function(o){return Object.keys(o).map(function(k){return o[k];});};}
if(!Number.isFinite){Number.isFinite=function(v){return typeof v==='number'&&isFinite(v);};}
(function(){function nodes(a){var f=document.createDocumentFragment();for(var i=0;i<a.length;i++){var n=a[i];f.appendChild(n instanceof Node?n:document.createTextNode(String(n)));}return f;}
[Element.prototype,DocumentFragment.prototype,Document.prototype].forEach(function(p){
if(!p.append)p.append=function(){this.appendChild(nodes(arguments));};
if(!p.prepend)p.prepend=function(){this.insertBefore(nodes(arguments),this.firstChild);};
if(!p.replaceChildren)p.replaceChildren=function(){while(this.firstChild)this.removeChild(this.firstChild);this.appendChild(nodes(arguments));};});
var E=Element.prototype;
if(!E.before)E.before=function(){if(this.parentNode)this.parentNode.insertBefore(nodes(arguments),this);};
if(!E.after)E.after=function(){if(this.parentNode)this.parentNode.insertBefore(nodes(arguments),this.nextSibling);};
if(!E.replaceWith)E.replaceWith=function(){if(this.parentNode)this.parentNode.replaceChild(nodes(arguments),this);};
if(!E.remove)E.remove=function(){if(this.parentNode)this.parentNode.removeChild(this);};
if(!E.matches)E.matches=E.webkitMatchesSelector;
if(!E.closest)E.closest=function(s){var e=this;while(e&&e.nodeType===1){if(e.matches(s))return e;e=e.parentNode;}return null;};
if(!E.toggleAttribute)E.toggleAttribute=function(n,f){if(f===undefined)f=!this.hasAttribute(n);if(f)this.setAttribute(n,'');else this.removeAttribute(n);return f;};})();
if(!Array.prototype.flat){Array.prototype.flat=function(){return [].concat.apply([],this);};}
if(!Math.trunc){Math.trunc=function(v){return v<0?Math.ceil(v):Math.floor(v);};}
if(!Number.isNaN){Number.isNaN=function(v){return v!==v;};}
if(window.NodeList&&!NodeList.prototype.forEach){NodeList.prototype.forEach=Array.prototype.forEach;}`
].join('\n;\n');
html = html.replace(/<head>/i, '<head><script>' + poly.replace(/<\/script/gi, '<\\/script') + '</script>');
fs.writeFileSync(out, html);
console.log('legacy ok', out, Math.round(html.length / 1024) + 'KB', 'vars=' + Object.keys(vars).length);
