'use strict';

const TARGET_CLASSES = [
  'com.yuewen.ywlogin.login.YWLoginManager',
  'com.stub.StubApp',
  'com.qihoo.util.StubApp',
  'com.yuewen.fock.Fock'
];

const TARGET_METHOD_NAMES = [
  'getInstance',
  'pwdLogin',
  'sendPhoneCode',
  'qrCodeV2',
  'interface11',
  'interface20'
];

const JNI_REGISTER_NATIVES_INDEX = 215;
const PTR_SIZE = Process.pointerSize;
const JIAGU_RELATIVE_WINDOW = 0x800000;

function findJiaguModule() {
  try {
    return Process.findModuleByName('libjiagu_vip.so');
  } catch (_) {
    return null;
  }
}

function ptrString(value) {
  if (value === null || value === undefined) {
    return '0x0';
  }
  try {
    return ptr(value).toString();
  } catch (_) {
    return String(value);
  }
}

function readCStringSafe(address) {
  if (address === null || address === undefined) {
    return null;
  }
  try {
    const p = ptr(address);
    if (p.isNull()) {
      return null;
    }
    return p.readCString();
  } catch (e) {
    return '<read-error:' + e + '>';
  }
}

function describeAddress(address) {
  const p = ptr(address);
  const jiagu = findJiaguModule();
  const result = {
    ptr: p.toString(),
    module: null,
    path: null,
    base: null,
    offset: null,
    jiaguBase: jiagu !== null ? jiagu.base.toString() : null,
    jiaguOffset: null,
    symbol: null
  };

  const module = Process.findModuleByAddress(p);
  if (module !== null) {
    result.module = module.name;
    result.path = module.path;
    result.base = module.base.toString();
    result.offset = '0x' + p.sub(module.base).toString(16);
  }
  if (jiagu !== null) {
    const start = jiagu.base;
    const end = jiagu.base.add(JIAGU_RELATIVE_WINDOW);
    if (p.compare(start) >= 0 && p.compare(end) < 0) {
      result.jiaguOffset = '0x' + p.sub(start).toString(16);
    }
  }

  const symbol = DebugSymbol.fromAddress(p);
  if (symbol !== null && symbol.name !== null) {
    result.symbol = symbol.name;
  }
  return result;
}

function shouldTrackClass(className) {
  if (className === null || className === undefined) {
    return false;
  }
  if (TARGET_CLASSES.indexOf(className) >= 0) {
    return true;
  }
  return className.indexOf('YWLogin') >= 0 || className.indexOf('yuewen.ywlogin') >= 0;
}

function shouldTrackMethod(name) {
  if (name === null || name === undefined) {
    return false;
  }
  return TARGET_METHOD_NAMES.indexOf(name) >= 0;
}

function getClassNameSafe(clazz) {
  try {
    const env = Java.vm.tryGetEnv();
    if (env !== null) {
      return env.getClassName(clazz);
    }
  } catch (e) {
    return '<class-error:' + e + '>';
  }
  return '<class-env-unavailable>';
}

function readMethods(methodsPtr, nMethods) {
  const methods = [];
  const count = Math.max(0, Math.min(nMethods, 256));
  const entrySize = PTR_SIZE * 3;
  for (let i = 0; i < count; i++) {
    const entry = methodsPtr.add(i * entrySize);
    const namePtr = entry.readPointer();
    const sigPtr = entry.add(PTR_SIZE).readPointer();
    const fnPtr = entry.add(PTR_SIZE * 2).readPointer();
    const name = readCStringSafe(namePtr);
    const signature = readCStringSafe(sigPtr);
    const fn = describeAddress(fnPtr);
    methods.push({
      index: i,
      name: name,
      signature: signature,
      fnPtr: fn.ptr,
      module: fn.module,
      path: fn.path,
      base: fn.base,
      offset: fn.offset,
      jiaguBase: fn.jiaguBase,
      jiaguOffset: fn.jiaguOffset,
      symbol: fn.symbol
    });
  }
  return methods;
}

function dumpInterestingModules() {
  const modules = Process.enumerateModules()
    .filter(function (m) {
      const name = m.name.toLowerCase();
      const path = m.path.toLowerCase();
      return name.indexOf('jiagu') >= 0 ||
        name.indexOf('fock') >= 0 ||
        name.indexOf('yw') >= 0 ||
        path.indexOf('com.qq.reader') >= 0;
    })
    .map(function (m) {
      return {
        name: m.name,
        path: m.path,
        base: m.base.toString(),
        size: m.size
      };
    });
  console.log('RN_MODULES ' + JSON.stringify(modules));
}

function installRegisterNativesHook() {
  Java.perform(function () {
    const env = Java.vm.getEnv();
    const table = env.handle.readPointer();
    const registerNatives = table.add(JNI_REGISTER_NATIVES_INDEX * PTR_SIZE).readPointer();

    console.log('RN_HOOK RegisterNatives=' + registerNatives + ' table=' + table + ' index=' + JNI_REGISTER_NATIVES_INDEX);
    dumpInterestingModules();

    Interceptor.attach(registerNatives, {
      onEnter: function (args) {
        const clazz = args[1];
        const methodsPtr = args[2];
        const nMethods = args[3].toInt32();
        if (methodsPtr.isNull() || nMethods <= 0 || nMethods > 1024) {
          this.record = null;
          return;
        }

        const className = getClassNameSafe(clazz);
        const methods = readMethods(methodsPtr, nMethods);
        const hasInterestingMethod = methods.some(function (m) {
          return shouldTrackMethod(m.name);
        });

        if (!shouldTrackClass(className) && !hasInterestingMethod) {
          this.record = null;
          return;
        }

        const caller = describeAddress(this.context.lr || this.returnAddress);
        this.record = {
          event: 'RegisterNatives',
          when: new Date().toISOString(),
          className: className,
          nMethods: nMethods,
          clazz: ptrString(clazz),
          methodsPtr: ptrString(methodsPtr),
          caller: caller,
          methods: methods
        };
        console.log('RN_CAPTURE ' + JSON.stringify(this.record));
      },
      onLeave: function (retval) {
        if (this.record !== null && this.record !== undefined) {
          console.log('RN_RESULT ' + JSON.stringify({
            when: new Date().toISOString(),
            className: this.record.className,
            nMethods: this.record.nMethods,
            retval: retval.toInt32()
          }));
        }
      }
    });
  });
}

setImmediate(installRegisterNativesHook);
