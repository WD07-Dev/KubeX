function KubeXPromise(executor) {
    var self = this;
    var state = 0;
    var value;
    var handlers = [];
    var claimed = false;
    var handled = false;
    var rejection;

    function resolve(nextValue) {
        if(state !== 0) return;
        if(nextValue === self) {
            reject(new Error("KubeXPromise cannot resolve itself"));
            return;
        }
        if(nextValue && (typeof nextValue === "object" || typeof nextValue === "function")) {
            var then;
            try {
                then = nextValue.then;
            } catch(error) {
                reject(error);
                return;
            }
            if(typeof then === "function") {
                var called = false;
                try {
                    then.call(nextValue, function (result) {
                        if(called) return;
                        called = true;
                        resolve(result);
                    }, function (error) {
                        if(called) return;
                        called = true;
                        reject(error);
                    });
                } catch(error) {
                    if(!called) reject(error);
                }
                return;
            }
        }
        state = 1;
        value = nextValue;
        flush();
    }

    function reject(error) {
        if(state !== 0) return;
        state = 2;
        value = error;
        flush();
        if(!handled) rejection = __kubexTrackRejection(error, new Error());
    }

    function flush() {
        var list = handlers.slice();
        handlers.length = 0;
        for(var index = 0; index < list.length; index++) {
            list[index](state, value);
        }
    }

    this.__kxThen = function (handler) {
        handled = true;
        if(rejection) rejection.cancel();
        if(state === 0) handlers.push(handler);
        else handler(state, value);
    };

    this.then = function (onFulfilled, onRejected) {
        return new KubeXPromise(function (resolve, reject) {
            self.__kxThen(function (nextState, nextValue) {
                try {
                    if(nextState === 1) {
                        if(typeof onFulfilled !== "function") {
                            resolve(nextValue);
                            return;
                        }
                        resolve(onFulfilled(nextValue));
                        return;
                    }
                    if(typeof onRejected !== "function") {
                        reject(nextValue);
                        return;
                    }
                    resolve(onRejected(nextValue));
                } catch(error) {
                    reject(error);
                }
            });
        });
    };

    this.catch = function (onRejected) {
        return self.then(undefined, onRejected);
    };

    this.finally = function (onFinally) {
        var runFinally = typeof onFinally === "function" ? onFinally : function () {};
        return self.then(function (nextValue) {
            return KubeXPromise.resolve(runFinally()).then(function () {
                return nextValue;
            });
        }, function (error) {
            return KubeXPromise.resolve(runFinally()).then(function () {
                return KubeXPromise.reject(error);
            });
        });
    };

    try {
        executor(function(value) {
            if(claimed) return;
            claimed = true;
            resolve(value);
        }, function(error) {
            if(claimed) return;
            claimed = true;
            reject(error);
        });
    } catch(error) {
        if(!claimed) {
            claimed = true;
            reject(error);
        }
    }
}

var __kubexPromiseBridge;
var __kubexPromiseBridgeChecked = false;

function __kubexTrackRejection(error, origin) {
    try {
        if(typeof Java === "undefined") return null;
        if(!__kubexPromiseBridgeChecked) {
            __kubexPromiseBridgeChecked = true;
            // Exported scripts can run without the KubeX mod installed.
            try {
                __kubexPromiseBridge = Java.loadClass("com.ourgram.kubex.runtime.RuntimeBridge");
            } catch(unavailable) {}
        }
        if(!__kubexPromiseBridge) return null;
        if(error && error.__kubexLocation) {
            var location = error.__kubexLocation;
            return __kubexPromiseBridge.trackUnhandledAt(String(error), "${promiseScriptGroup}", location[0], location[1], location[2]);
        }
        if(typeof Error.captureStackTrace === "function") Error.captureStackTrace(origin);
        var stack = error && error.stack ? String(error.stack) : String(origin.stack || "");
        var frames = /main\.js:(\d+)(?::(\d+))?/g;
        var frame, line = 0, column = 1;
        while((frame = frames.exec(stack))) {
            if(Number(frame[1]) <= __kubexPromiseRuntimeEnd) continue;
            line = Number(frame[1]);
            column = frame[2] ? Number(frame[2]) : 1;
            break;
        }
        return __kubexPromiseBridge.trackUnhandled(String(error), "${promiseScriptGroup}", line, column);
    } catch(reportError) {
        if(typeof console !== "undefined" && console.error) {
            console.error("[KubeX] Promise rejection tracking failed: " + String(reportError));
        }
        return null;
    }
}

function __kubexCaptureError(error) {
    try {
        if(error && typeof error === "object" && !error.stack && typeof Error.captureStackTrace === "function") {
            Error.captureStackTrace(error);
        }
    } catch(ignored) {}
    return error;
}

function __kubexAt(action, location) {
    try {
        return action();
    } catch(error) {
        try {
            if(error && (typeof error === "object" || typeof error === "function") && !error.__kubexLocation) {
                Object.defineProperty(error, "__kubexLocation", {value: location});
            }
        } catch(ignored) {}
        throw error;
    }
}

KubeXPromise.resolve = function (value) {
    return new KubeXPromise(function (resolve) {
        resolve(value);
    });
};

KubeXPromise.reject = function (error) {
    return new KubeXPromise(function (resolve, reject) {
        reject(error);
    });
};

KubeXPromise.all = function (values) {
    return new KubeXPromise(function (resolve, reject) {
        var results = [];
        var remaining = values.length;
        if(remaining === 0) {
            resolve(results);
            return;
        }
        for(var index = 0; index < values.length; index++) {
            (function (slot) {
                __kubexAwait(values[slot], function (result) {
                    results[slot] = result;
                    remaining--;
                    if(remaining === 0) resolve(results);
                }, reject);
            })(index);
        }
    });
};

KubeXPromise.race = function (values) {
    return new KubeXPromise(function (resolve, reject) {
        for(var index = 0; index < values.length; index++) {
            __kubexAwait(values[index], resolve, reject);
        }
    });
};

KubeXPromise.allSettled = function (values) {
    return new KubeXPromise(function (resolve) {
        var results = [];
        var remaining = values.length;
        if(remaining === 0) {
            resolve(results);
            return;
        }
        for(var index = 0; index < values.length; index++) {
            (function (slot) {
                __kubexAwait(values[slot], function (result) {
                    results[slot] = { status: "fulfilled", value: result };
                    remaining--;
                    if(remaining === 0) resolve(results);
                }, function (error) {
                    results[slot] = { status: "rejected", reason: error };
                    remaining--;
                    if(remaining === 0) resolve(results);
                });
            })(index);
        }
    });
};

KubeXPromise.any = function (values) {
    return new KubeXPromise(function (resolve, reject) {
        var errors = [];
        var remaining = values.length;
        if(remaining === 0) {
            reject(new Error("All promises were rejected"));
            return;
        }
        for(var index = 0; index < values.length; index++) {
            (function (slot) {
                __kubexAwait(values[slot], resolve, function (error) {
                    errors[slot] = error;
                    remaining--;
                    if(remaining === 0) {
                        var aggregate = new Error("All promises were rejected");
                        aggregate.errors = errors;
                        reject(aggregate);
                    }
                });
            })(index);
        }
    });
};

var __kubexAwait = function (value, success, error) {
    try {
        KubeXPromise.resolve(value).__kxThen(function (state, result) {
            if(state === 1) success(result);
            else if(error) error(result);
            else throw result;
        });
    } catch(exception) {
        if(error) error(exception);
        else throw exception;
    }
};
var __kubexPromiseRuntimeEnd = 0;