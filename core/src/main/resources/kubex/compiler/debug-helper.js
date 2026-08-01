var __kubexRuntime = typeof Java !== "undefined" ? Java.loadClass("com.ourgram.kubex.neoforge.KubeXRuntimeBridge") : null;
var __kubexReport = typeof __kubexReport === "function" ? __kubexReport : function (error, meta) {
    var detail = error && error.message ? error.message : String(error);
    var stackText = error && error.stack ? String(error.stack) : detail;
    var match = /main\.js:(\d+)(?::(\d+))?/.exec(stackText);
    var generatedLine = match ? parseInt(match[1], 10) : 0;
    var generatedColumn = match && match[2] ? parseInt(match[2], 10) : 1;
    var message = "[KubeX Debug] " + meta.file + ":" + meta.line + ":" + meta.column + " " + detail;
    if(__kubexRuntime) {
        try {
            message = String(__kubexRuntime.report(
                error,
                meta.scriptGroup,
                generatedLine,
                generatedColumn,
                meta.file,
                meta.line,
                meta.column
            ));
        } catch (bridgeError) {
            message = message + "\n[KubeX Debug] reporter failed: " + String(bridgeError);
        }
    }
    if(typeof console !== "undefined" && console.error) {
        console.error(message);
        return;
    }
    if(typeof console !== "undefined" && console.log) {
        console.log(message);
    }
};