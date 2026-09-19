package com.glass.lisn.engine.lx

/** lx 协议 QuickJS 前奏:Buffer 垫片 + 全局 lx 对象(JS 侧胶水,Kotlin 提供原语) */
object LxPrelude {

    /** Buffer 等基础垫片(hex 为规范表示,base64/utf8 经 Kotlin 转换) */
    const val PRELUDE: String = """
    (function(){
      var G = globalThis;
      function LXBuffer(hex){ this.__hex = hex; }
      LXBuffer.prototype.toString = function(enc){
        enc = (enc || 'utf8').toLowerCase();
        if (enc === 'hex') return this.__hex;
        if (enc === 'base64') return G.__lxHexToB64(this.__hex);
        return G.__lxBytesToUtf8(this.__hex);
      };
      Object.defineProperty(LXBuffer.prototype, 'length', { get: function(){ return this.__hex.length / 2; } });
      LXBuffer.from = function(data, enc){
        enc = (enc || 'utf8').toLowerCase();
        if (data && data.__hex !== undefined) return new LXBuffer(data.__hex);
        if (enc === 'hex') return new LXBuffer(String(data).toLowerCase());
        if (enc === 'base64') return new LXBuffer(G.__lxB64ToHex(String(data)));
        return new LXBuffer(G.__lxBytesFromUtf8(String(data)));
      };
      LXBuffer.isBuffer = function(x){ return x instanceof LXBuffer; };
      LXBuffer.alloc = function(n){ return new LXBuffer(new Array(n).fill(0).map(function(){ return 0; }).join('').replace(/0/g, '00')); };
      G.Buffer = LXBuffer;
      G.__toHex = function(x){
        if (x instanceof LXBuffer) return x.__hex;
        if (x && typeof x === 'object' && x.__hex !== undefined) return x.__hex;
        return G.__lxBytesFromUtf8(String(x));
      };
    })();
    """

    /** 全局 lx 对象(协议实现) */
    const val LX_GLOBAL_JS: String = """
    (function(){
      var G = globalThis;
      var meta = G.__lxScriptMeta();
      function log(){ var a = Array.prototype.slice.call(arguments);
        G.__lxLog(a.map(function(x){ return (typeof x === 'object') ? JSON.stringify(x).slice(0,300) : String(x); }).join(' ')); }

      G.console = { log: log, info: log, debug: log,
        warn: log, error: log, group: log, groupCollapsed: log, groupEnd: function(){} };

      G.__lxHandle = null;

      G.lx = {
        EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
        env: 'mobile',
        version: '2.0.0',
        currentScriptInfo: { name: meta[1], version: meta[2] || '', description: '', author: '', homepage: '' },
        on: function(name, handler){
          if (name === 'request'){ G.__lxHandle = handler; G.__lxMarkHandler(); }
        },
        send: function(name, data){
          if (name === 'inited'){ G.__lxOnInited(data); }
          else if (name === 'updateAlert'){ log('updateAlert:', data && data.description ? data.description : ''); }
          else { log('send:', name); }
        },
        request: function(url, options, cb){
          var cfg = {};
          Object.assign(cfg, options || {});
          cfg.url = url;
          G.__lxRequestAsync(cfg).then(function(resp){
            try { cb(null, resp); } catch (e) { log('callback error: ' + e); }
          }).catch(function(e){
            try { cb(e instanceof Error ? e : new Error(String(e))); } catch (e2) { log('callback error: ' + e2); }
          });
          return function(){};
        },
        utils: {
          buffer: {
            from: function(data, enc){ return G.Buffer.from(data, enc); },
            bufToString: function(buf, format){ return (buf && buf.toString) ? buf.toString(format || 'utf8') : String(buf); }
          },
          crypto: {
            aesEncrypt: function(data, mode, key, iv){ return new G.Buffer(G.__lxAes(G.__toHex(data), mode, G.__toHex(key), iv ? G.__toHex(iv) : '', '1')); },
            aesDecrypt: function(data, mode, key, iv){ return new G.Buffer(G.__lxAes(G.__toHex(data), mode, G.__toHex(key), iv ? G.__toHex(iv) : '', '0')); },
            md5: function(x){ return G.__lxMd5(G.__toHex(x)); },
            randomBytes: function(n){ return new G.Buffer(G.__lxRandomBytes(n)); },
            rsaEncrypt: function(data, key){ return new G.Buffer(G.__lxRsaEncrypt(G.__toHex(data), typeof key === 'string' ? key : (key && key.toString ? key.toString() : ''))); }
          },
          zlib: {
            inflate: function(b){ return G.__lxZlibInflate(G.__toHex(b)).then ? G.__lxZlibInflate(G.__toHex(b)) : Promise.resolve(new G.Buffer(G.__lxZlibInflate(G.__toHex(b)))); },
            inflateRaw: function(b){ return Promise.resolve(new G.Buffer(G.__lxZlibInflateRaw(G.__toHex(b)))); },
            deflate: function(b){ return Promise.resolve(new G.Buffer(G.__lxZlibDeflate(G.__toHex(b)))); }
          }
        }
      };
      G.lx.utils.zlib.inflate = function(b){ return Promise.resolve(new G.Buffer(G.__lxZlibInflate(G.__toHex(b)))); };
      G.setTimeout = function(fn, ms){
        G.__lxDelay(Number(ms) || 0).then(function(){ try { fn(); } catch (e) { G.__lxLog('timer cb: ' + e); } });
        return 0;
      };
      G.clearTimeout = function(){};
      G.setInterval = function(){ return 0; };
      G.clearInterval = function(){};
    })();
    """

    /** 宿主元信息绑定名(由 Kotlin 注入 [id, displayName, version]) */
    const val META_FN: String = "__lxScriptMeta"
}
