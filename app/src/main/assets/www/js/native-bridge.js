(function () {
  const callbacks = {};
  let callbackSeq = 0;

  window.__onNativeCallback = function (callbackId, resultJson) {
    const callback = callbacks[callbackId];
    if (!callback) return;
    delete callbacks[callbackId];
    try {
      const result = JSON.parse(resultJson);
      callback.resolve(result);
    } catch (error) {
      callback.reject(error);
    }
  };

  function invoke(method, params) {
    return new Promise(function (resolve, reject) {
      if (!window.NativeBridge) {
        reject(new Error("NativeBridge unavailable"));
        return;
      }
      const callbackId = "cb_" + (++callbackSeq) + "_" + Date.now();
      callbacks[callbackId] = { resolve: resolve, reject: reject };
      try {
        window.NativeBridge.call(
          method,
          JSON.stringify(params || {}),
          callbackId
        );
      } catch (error) {
        delete callbacks[callbackId];
        reject(error);
      }
    });
  }

  window.AppNative = {
    ping: function () {
      return Promise.resolve(JSON.parse(window.NativeBridge.ping()));
    },
    getShellInfo: function () {
      return Promise.resolve(JSON.parse(window.NativeBridge.getShellInfo()));
    },
    call: invoke,
    storage: {
      getConnections: function () { return invoke("storage.getConnections"); },
      saveConnection: function (data) { return invoke("storage.saveConnection", data); },
      deleteConnection: function (id) { return invoke("storage.deleteConnection", { id: id }); },
      getSetting: function (key) { return invoke("storage.getSetting", { key: key }); },
      setSetting: function (key, value) { return invoke("storage.setSetting", { key: key, value: value }); }
    },
    ssh: {
      connect: function (params) { return invoke("ssh.connect", params); },
      connectById: function (id) { return invoke("ssh.connectById", { id: id }); },
      exec: function (sessionKey, command, timeoutSeconds) {
        return invoke("ssh.exec", { sessionKey: sessionKey, command: command, timeoutSeconds: timeoutSeconds || 120 });
      },
      disconnect: function (sessionKey) { return invoke("ssh.disconnect", { sessionKey: sessionKey }); },
      isConnected: function (sessionKey) { return invoke("ssh.isConnected", { sessionKey: sessionKey }); }
    },
    sftp: {
      upload: function (sessionKey, localPath, remotePath) {
        return invoke("sftp.upload", { sessionKey: sessionKey, localPath: localPath, remotePath: remotePath });
      },
      download: function (sessionKey, remotePath, localPath) {
        return invoke("sftp.download", { sessionKey: sessionKey, remotePath: remotePath, localPath: localPath });
      },
      list: function (sessionKey, remotePath) {
        return invoke("sftp.list", { sessionKey: sessionKey, remotePath: remotePath || "/" });
      },
      readText: function (sessionKey, remotePath) {
        return invoke("sftp.readText", { sessionKey: sessionKey, remotePath: remotePath });
      },
      writeText: function (sessionKey, remotePath, content) {
        return invoke("sftp.writeText", { sessionKey: sessionKey, remotePath: remotePath, content: content });
      },
      mkdirs: function (sessionKey, remotePath) {
        return invoke("sftp.mkdirs", { sessionKey: sessionKey, remotePath: remotePath });
      }
    },
    hotupdate: {
      getVersion: function () { return invoke("hotupdate.getVersion"); },
      applyZipPath: function (zipPath) { return invoke("hotupdate.applyZipPath", { zipPath: zipPath }); },
      applyZipBase64: function (base64) { return invoke("hotupdate.applyZipBase64", { base64: base64 }); },
      reload: function () { return invoke("hotupdate.reload"); }
    },
    agent: {
      upload: function (sessionKey, localPath, remotePath) {
        return invoke("agent.upload", { sessionKey: sessionKey, localPath: localPath, remotePath: remotePath });
      },
      install: function (params) { return invoke("agent.install", params); },
      reinstall: function (params) { return invoke("agent.reinstall", params); }
    },
    file: {
      pick: function (mimeType) {
        return invoke("file.pick", { mimeType: mimeType || "*/*" });
      }
    }
  };
})();
