@file:OptIn(com.dokar.quickjs.ExperimentalQuickJsApi::class)

package com.glass.lisn.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.delay

/**
 * MusicFree 插件沙箱垫片。
 * MusicFree 插件是 CommonJS 模块(module.exports),运行时可用
 * require('axios'|'he'|'crypto-js'|'cheerio'|'qs'|'big-integer'|'dayjs')。
 * 网络/密码学/HTML 解析由 Kotlin 宿主实现,JS 侧只做胶水。
 */
object MfShims {

    private fun log(id: String, level: String, args: Array<Any?>) {
        val msg = args.joinToString(" ") { a ->
            when (a) {
                null -> "null"
                is Map<*, *>, is List<*> -> a.toString().take(200)
                else -> str(a) ?: "?"
            }
        }
        LogProvider.log("mf:$id", "$level $msg")
    }

    /** 在 QuickJS 实例上注入全部宿主绑定 + JS 垫片胶水 */
    fun install(qjs: QuickJs, id: String) {
        // ---------- Kotlin 原生绑定 ----------
        qjs.define("console") {
            function("log") { args -> log(id, "I", args) }
            function("warn") { args -> log(id, "W", args) }
            function("error") { args -> log(id, "E", args) }
        }
        qjs.asyncFunction("__httpRequest") { args ->
            val cfg = args.firstOrNull() as? Map<*, *> ?: emptyMap<Any?, Any?>()
            @Suppress("UNCHECKED_CAST")
            httpRequestShim(cfg as Map<String, Any?>)
        }
        qjs.asyncFunction("__delay") { args ->
            delay((num(args.firstOrNull()) ?: 0.0).toLong().coerceIn(0, 60_000))
            null
        }
        qjs.function("__md5Hex") { a -> md5Hex(str(a[0]) ?: "") }
        qjs.function("__sha1Hex") { a -> sha1Hex(str(a[0]) ?: "") }
        qjs.function("__sha256Hex") { a -> sha256Hex(str(a[0]) ?: "") }
        qjs.function("__utf8ToHex") { a -> utf8ToHex(str(a[0]) ?: "") }
        qjs.function("__latin1ToHex") { a -> latin1ToHex(str(a[0]) ?: "") }
        qjs.function("__b64ToHex") { a -> b64ToHex(str(a[0]) ?: "") }
        qjs.function("__hexToB64") { a -> hexToB64(str(a[0]) ?: "") }
        qjs.function("__hexToUtf8") { a -> hexToUtf8(str(a[0]) ?: "") }
        qjs.function("__aesEncryptHex") { a ->
            aesEncryptHexToB64(str(a[0]) ?: "", str(a[1]) ?: "", str(a[2]), str(a[3]) ?: "CBC")
        }
        qjs.function("__bigStrToHex") { a -> bigStrToHex(str(a[0]) ?: "", (num(a[1]) ?: 10.0).toInt()) }
        qjs.function("__bigNumToHex") { a -> bigNumToHex(num(a[0]) ?: 0.0) }
        qjs.function("__bigModPow") { a -> bigModPow(str(a[0]) ?: "", str(a[1]) ?: "", str(a[2]) ?: "") }
        qjs.function("__bigToString") { a -> bigToString(str(a[0]) ?: "", (num(a[1]) ?: 10.0).toInt()) }
        qjs.function("__bigMultiply") { a -> bigMultiply(str(a[0]) ?: "", str(a[1]) ?: "") }
        qjs.function("__bigMod") { a -> bigMod(str(a[0]) ?: "", str(a[1]) ?: "") }
        qjs.function("__htmlParse") { a -> htmlTreeJson(str(a[0]) ?: "") }
    }

    /** JS 垫片胶水:require 注册表 + axios/he/crypto-js/cheerio/qs/big-integer/dayjs + setTimeout */
    val PRELUDE = """
    (function(){
      var G = globalThis;
      // ---------- he ----------
      var HE_MAP = {'&amp;':'&','&lt;':'<','&gt;':'>','&quot;':'\"','&#39;':"'",'&apos;':"'",
        '&nbsp;':' ','&copy;':'©','&hellip;':'…','&mdash;':'—','&ndash;':'–',
        '&laquo;':'«','&raquo;':'»','&ldquo;':'“','&rdquo;':'”','&lsquo;':'‘','&rsquo;':'’'};
      function heDecode(s){
        s = String(s == null ? '' : s);
        s = s.replace(/&#x([0-9a-fA-F]+);/g, function(m,h){ return String.fromCodePoint(parseInt(h,16)); });
        s = s.replace(/&#([0-9]+);/g, function(m,d){ return String.fromCodePoint(parseInt(d,10)); });
        return s.replace(/&[a-zA-Z]+;/g, function(m){ return HE_MAP[m] !== undefined ? HE_MAP[m] : m; });
      }
      function heEncode(s){
        return String(s == null ? '' : s).replace(/[&<>"']/g, function(c){
          return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; });
      }
      // ---------- axios ----------
      function axiosRequest(cfg){
        if (typeof cfg === 'string') cfg = { url: cfg };
        return G.__httpRequest(cfg).then(function(s){ return JSON.parse(s); });
      }
      var axiosInst = function(cfg){ return axiosRequest(cfg); };
      axiosInst.get = function(u, c){ var k = {}; Object.assign(k, c || {}); k.url = u; k.method = 'get'; return axiosRequest(k); };
      axiosInst.post = function(u, d, c){ var k = {}; Object.assign(k, c || {}); k.url = u; k.data = d; k.method = 'post'; return axiosRequest(k); };
      axiosInst.default = axiosInst;
      // ---------- crypto-js(常用子集) ----------
      function WA(hex){ return { __hex: hex }; }
      var CryptoJs = {
        enc: {
          Utf8: { parse: function(s){ return WA(G.__utf8ToHex(String(s))); } },
          Base64: { parse: function(s){ return WA(G.__b64ToHex(String(s))); } },
          Hex: { parse: function(s){ return WA(String(s).toLowerCase()); } },
          Latin1: { parse: function(s){ return WA(G.__latin1ToHex(String(s))); } }
        },
        mode: { CBC: 'CBC', ECB: 'ECB', CFB: 'CFB' },
        pad: { Pkcs7: 'Pkcs7' },
        MD5: function(s){ return WA(G.__md5Hex(String(s))); },
        SHA1: function(s){ return WA(G.__sha1Hex(String(s))); },
        SHA256: function(s){ return WA(G.__sha256Hex(String(s))); },
        AES: {
          encrypt: function(data, key, cfg){
            var mode = (cfg && cfg.mode) || CryptoJs.mode.CBC;
            var ivHex = (cfg && cfg.iv && cfg.iv.__hex) ? cfg.iv.__hex : null;
            if (!key || !key.__hex) throw new Error('crypto-js 垫片:仅支持 WordArray 密钥');
            var dHex = (data && data.__hex) ? data.__hex : G.__utf8ToHex(String(data));
            var b64 = G.__aesEncryptHex(dHex, key.__hex, ivHex, mode);
            return { toString: function(){ return b64; }, ciphertext: WA(b64) };
          }
        }
      };
      // ---------- qs ----------
      function encQ(v){ return encodeURIComponent(String(v)); }
      function qsStringify(o, prefix){
        var out = [];
        for (var k in o){
          if (!Object.prototype.hasOwnProperty.call(o, k)) continue;
          var v = o[k];
          if (v === undefined) continue;
          var key = prefix ? prefix + '[' + k + ']' : k;
          if (v !== null && typeof v === 'object') out.push(qsStringify(v, key));
          else out.push(encQ(key) + '=' + encQ(v === null ? '' : v));
        }
        return out.join('&');
      }
      // ---------- big-integer ----------
      function fromHex(hex){
        return {
          __bigint: hex,
          modPow: function(e, m){ return fromHex(G.__bigModPow(hex, e.__bigint, m.__bigint)); },
          multiply: function(o){ return fromHex(G.__bigMultiply(hex, o.__bigint || G.__bigStrToHex(String(o), 10))); },
          mod: function(o){ return fromHex(G.__bigMod(hex, o.__bigint || G.__bigStrToHex(String(o), 10))); },
          toString: function(r){ if (r === 16) return hex; return G.__bigToString(hex, r || 10); }
        };
      }
      function BigInt_(v, radix){
        if (v && typeof v === 'object' && v.__bigint) return v;
        var hex;
        if (typeof v === 'number') hex = G.__bigNumToHex(v);
        else hex = G.__bigStrToHex(String(v), radix || 10);
        return fromHex(hex);
      }
      // ---------- dayjs ----------
      function pad2(n){ return (n < 10 ? '0' : '') + n; }
      function dayjs(v){
        var d = v instanceof Date ? v : new Date(v === undefined ? Date.now() : (typeof v === 'number' ? v : String(v)));
        return {
          format: function(f){
            return String(f)
              .replace(/YYYY/g, d.getFullYear())
              .replace(/MM/g, pad2(d.getMonth() + 1))
              .replace(/DD/g, pad2(d.getDate()))
              .replace(/HH/g, pad2(d.getHours()))
              .replace(/mm/g, pad2(d.getMinutes()))
              .replace(/ss/g, pad2(d.getSeconds()));
          },
          unix: function(){ return Math.floor(d.getTime() / 1000); },
          valueOf: function(){ return d.getTime(); }
        };
      }
      dayjs.unix = function(sec){ return dayjs(Number(sec) * 1000); };
      // ---------- cheerio(基于宿主 jsoup 树) ----------
      function allText(n){ return (n && n.text) ? n.text : ''; }
      function parseSel(sel){
        var tag = null, classes = [], id = null;
        var parts = String(sel).split('.');
        var first = parts.shift();
        var mTag = first.match(/^([a-zA-Z][a-zA-Z0-9]*)/);
        if (mTag) tag = mTag[1].toLowerCase();
        var mId = first.match(/#([\w-]+)/);
        if (mId) id = mId[1];
        for (var i = 0; i < parts.length; i++){ if (parts[i]) classes.push(parts[i]); }
        return { tag: tag, classes: classes, id: id };
      }
      function matches(node, sel){
        if (!node || !node.tag) return false;
        var s = parseSel(sel);
        if (s.tag && node.tag !== s.tag) return false;
        if (s.id && (!node.attrs || node.attrs['id'] !== s.id)) return false;
        if (s.classes.length){
          var cls = ((node.attrs && node.attrs['class']) || '').split(/\s+/);
          for (var i = 0; i < s.classes.length; i++){ if (cls.indexOf(s.classes[i]) === -1) return false; }
        }
        if (!s.tag && !s.id && !s.classes.length) return true;
        return true;
      }
      function collect(node, sel, out){
        if (matches(node, sel)) out.push(node);
        var ch = (node && node.children) || [];
        for (var i = 0; i < ch.length; i++) collect(ch[i], sel, out);
      }
      function wrap(nodes){
        var arr = nodes.slice();
        arr.text = function(){ return arr.map(function(n){ return allText(n); }).join(' ').trim(); };
        arr.html = function(){ return arr.map(function(n){ return allText(n); }).join(''); };
        arr.attr = function(a){ return arr.length ? (((arr[0].attrs || {})[a]) !== undefined ? String(arr[0].attrs[a]) : '') : ''; };
        arr.children = function(){
          var out = [];
          arr.forEach(function(n){ (n.children || []).forEach(function(c){ out.push(c); }); });
          return wrap(out);
        };
        arr.find = function(sel){ var out = []; arr.forEach(function(n){ collect(n, sel, out); }); return wrap(out); };
        arr.each = function(fn){ arr.forEach(function(n, i){ fn(i, wrap([n])); }); return arr; };
        arr.first = function(){ return wrap(arr.slice(0, 1)); };
        return arr;
      }
      function cheerioLoad(html){
        var tree = JSON.parse(G.__htmlParse(String(html)));
        var api = function(sel){
          if (typeof sel === 'object' && sel !== null && sel.tag) return wrap([sel]);
          if (typeof sel === 'string'){
            if (sel.charAt(0) === '.' || sel.charAt(0) === '#'){
              var out = []; collect({ tag: 'body', attrs: {}, children: tree, text: '' }, sel, out); return wrap(out);
            }
            var out2 = [];
            tree.forEach(function(n){ collect(n, sel, out2); });
            return wrap(out2);
          }
          return wrap([]);
        };
        api.text = function(){ return String(html).replace(/<[^>]+>/g, ''); };
        api.html = function(){ return String(html); };
        return api;
      }
      // ---------- 模块注册表 ----------
      var __modules = {
        'axios': axiosInst,
        'he': { decode: heDecode, encode: heEncode },
        'crypto-js': CryptoJs,
        'qs': {
          stringify: qsStringify,
          parse: function(s){
            var o = {};
            String(s).split('&').forEach(function(p){
              if (!p) return;
              var kv = p.split('=');
              o[decodeURIComponent(kv[0].replace(/\+/g, ' '))] = decodeURIComponent((kv[1] || '').replace(/\+/g, ' '));
            });
            return o;
          }
        },
        'big-integer': BigInt_,
        'dayjs': dayjs,
        'cheerio': { load: cheerioLoad }
      };
      G.require = function(name){
        var m = __modules[name];
        if (!m) throw new Error('LISN 宿主未提供依赖: ' + name);
        return m;
      };
      G.setTimeout = function(fn, ms){
        G.__delay(Number(ms) || 0).then(function(){ try { fn(); } catch (e) { G.console.error('setTimeout cb: ' + e); } });
        return 0;
      };
      G.clearTimeout = function(){};
      G.setInterval = function(){ return 0; };
      G.clearInterval = function(){};
      G.Buffer = G.Buffer || { from: function(){ throw new Error('LISN 宿主未提供 Buffer'); } };
    })();
    """.trimIndent()
}

// android.util.Log 包装(独立对象避免静态依赖问题)
object LogProvider {
    fun log(tag: String, msg: String) = android.util.Log.d(tag.take(23), msg.take(500))
}
