/* Polyfill navigator.hid (subset WebHID) di atas jembatan Android USB Host. */
(function () {
  'use strict';
  if (window.__dsHidInstalled || !window.AndroidHID) return;
  window.__dsHidInstalled = true;

  var pending = {};
  var seq = 0;

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = ++seq;
      pending[id] = { resolve: resolve, reject: reject };
      try {
        window.AndroidHID.call(method, JSON.stringify(args || {}), id);
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  window.__hidResult = function (id, ok, payload) {
    var p = pending[id];
    if (!p) return;
    delete pending[id];
    if (ok) {
      try { p.resolve(payload ? JSON.parse(payload) : null); } catch (e) { p.reject(e); }
    } else {
      p.reject(new Error(payload || 'USB error'));
    }
  };

  function toU8(d) {
    if (d instanceof Uint8Array) return d;
    if (ArrayBuffer.isView(d)) return new Uint8Array(d.buffer, d.byteOffset, d.byteLength);
    if (d instanceof ArrayBuffer) return new Uint8Array(d);
    return Uint8Array.from(d || []);
  }

  function b64(u8) {
    var s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    }
    return btoa(s);
  }

  function unb64(s) {
    var bin = atob(s), u8 = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
    return u8;
  }

  var devices = {};

  class HIDDevice extends EventTarget {
    constructor(info) {
      super();
      this._key = info.key;
      this.vendorId = info.vendorId;
      this.productId = info.productId;
      this.productName = info.productName;
      this.collections = info.collections;
      this.opened = false;
      this.oninputreport = null;
    }
    async open() {
      await call('open', { key: this._key });
      this.opened = true;
    }
    async close() {
      try { await call('close', { key: this._key }); } finally { this.opened = false; }
    }
    async sendReport(reportId, data) {
      await call('sendReport', { key: this._key, reportId: reportId, data: b64(toU8(data)) });
    }
    async sendFeatureReport(reportId, data) {
      await call('sendFeatureReport', { key: this._key, reportId: reportId, data: b64(toU8(data)) });
    }
    async receiveFeatureReport(reportId) {
      var r = await call('receiveFeatureReport', { key: this._key, reportId: reportId });
      var u8 = unb64(r.data);
      return new DataView(u8.buffer, 0, u8.length);
    }
  }

  function wrap(info) {
    var d = devices[info.key];
    if (!d) {
      d = new HIDDevice(info);
      devices[info.key] = d;
    }
    return d;
  }

  var hid = new EventTarget();
  hid.getDevices = async function () {
    var list = await call('getDevices');
    return list.map(wrap);
  };
  hid.requestDevice = async function (opts) {
    var filters = (opts && opts.filters) || [];
    var list = await call('requestDevice', { filters: filters });
    return list.map(wrap);
  };

  window.__hidInput = function (key, reportId, data) {
    var dev = devices[key];
    if (!dev) return;
    var u8 = unb64(data);
    var ev = new Event('inputreport');
    ev.device = dev;
    ev.reportId = reportId;
    ev.data = new DataView(u8.buffer, 0, u8.length);
    dev.dispatchEvent(ev);
    if (typeof dev.oninputreport === 'function') dev.oninputreport(ev);
  };

  window.__hidDisconnect = function (key) {
    var dev = devices[key];
    delete devices[key];
    if (!dev) return;
    dev.opened = false;
    var ev = new Event('disconnect');
    ev.device = dev;
    hid.dispatchEvent(ev);
  };

  Object.defineProperty(navigator, 'hid', { value: hid, configurable: true });
})();
