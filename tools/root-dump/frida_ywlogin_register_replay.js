'use strict';

const TARGET_CLASS = 'com.yuewen.ywlogin.login.YWLoginManager';
const REPLAY_TABLE = JSON.parse('[]');
const JNI_REGISTER_NATIVES_INDEX = 215;
const PTR_SIZE = Process.pointerSize;
const MAX_ATTEMPTS = 80;
const ATTEMPT_INTERVAL_MS = 250;

const allocations = [];
let resolvedFactory = null;
let resolvedLoaderDesc = null;

function findJiaguModule() {
  try {
    return Process.findModuleByName('libjiagu_vip.so');
  } catch (_) {
    return null;
  }
}

function getRegisterNativesPointer() {
  const env = Java.vm.getEnv();
  const table = env.handle.readPointer();
  return table.add(JNI_REGISTER_NATIVES_INDEX * PTR_SIZE).readPointer();
}

function parseOffset(value) {
  if (typeof value !== 'string' || value.length === 0) {
    return null;
  }
  if (value.indexOf('0x') === 0 || value.indexOf('0X') === 0) {
    return parseInt(value.substring(2), 16);
  }
  return parseInt(value, 16);
}

function normalizeRows() {
  const byKey = {};
  REPLAY_TABLE.forEach(function (row) {
    if (row.className !== TARGET_CLASS) {
      return;
    }
    if (!row.name || !row.signature || !row.jiaguOffset) {
      return;
    }
    const key = row.name + '\n' + row.signature;
    byKey[key] = row;
  });
  return Object.keys(byKey).sort().map(function (key) {
    return byKey[key];
  });
}

function describeLoader(loader) {
  if (loader === null || loader === undefined) {
    return '<bootstrap>';
  }
  try {
    return loader.toString();
  } catch (e) {
    return '<loader-toString-error:' + e + '>';
  }
}

function tryResolveWithFactory(factory, loader) {
  try {
    const klass = factory.use(TARGET_CLASS).class;
    resolvedFactory = factory;
    resolvedLoaderDesc = describeLoader(loader);
    console.log('RN_REPLAY_CLASS_RESOLVED loader=' + resolvedLoaderDesc);
    return klass;
  } catch (_) {
    return null;
  }
}

function resolveTargetClass() {
  if (resolvedFactory !== null) {
    try {
      return resolvedFactory.use(TARGET_CLASS).class;
    } catch (_) {
      resolvedFactory = null;
      resolvedLoaderDesc = null;
    }
  }

  const defaultClass = tryResolveWithFactory(Java.classFactory, Java.classFactory.loader);
  if (defaultClass !== null) {
    return defaultClass;
  }

  const loaders = Java.enumerateClassLoadersSync();
  for (let i = 0; i < loaders.length; i++) {
    const loader = loaders[i];
    try {
      loader.loadClass(TARGET_CLASS);
      const factory = Java.ClassFactory.get(loader);
      const klass = tryResolveWithFactory(factory, loader);
      if (klass !== null) {
        return klass;
      }
    } catch (_) {
    }
  }
  throw new Error('target class not found in any ClassLoader');
}

function getClassHandle() {
  const klass = resolveTargetClass();
  if (klass.$handle !== undefined) {
    return klass.$handle;
  }
  if (klass.handle !== undefined) {
    return klass.handle;
  }
  throw new Error('cannot resolve jclass handle for ' + TARGET_CLASS);
}

function buildNativeMethodArray(rows, jiaguBase) {
  const entrySize = PTR_SIZE * 3;
  const array = Memory.alloc(entrySize * rows.length);
  allocations.push(array);

  rows.forEach(function (row, index) {
    const off = parseOffset(row.jiaguOffset);
    if (off === null || isNaN(off)) {
      throw new Error('bad jiaguOffset for ' + row.name + ': ' + row.jiaguOffset);
    }

    const name = Memory.allocUtf8String(row.name);
    const signature = Memory.allocUtf8String(row.signature);
    const fnPtr = jiaguBase.add(off);
    allocations.push(name);
    allocations.push(signature);

    const entry = array.add(index * entrySize);
    entry.writePointer(name);
    entry.add(PTR_SIZE).writePointer(signature);
    entry.add(PTR_SIZE * 2).writePointer(fnPtr);

    console.log('RN_REPLAY_METHOD index=' + index +
      ' name=' + row.name +
      ' sig=' + row.signature +
      ' jiaguOffset=' + row.jiaguOffset +
      ' fn=' + fnPtr);
  });

  return array;
}

function replayOnce() {
  const jiagu = findJiaguModule();
  if (jiagu === null) {
    console.log('RN_REPLAY_WAIT reason=libjiagu_vip.so-missing');
    return false;
  }

  const rows = normalizeRows();
  if (rows.length === 0) {
    console.log('RN_REPLAY_ABORT reason=no-ywlogin-rows');
    return true;
  }

  let classHandle = null;
  try {
    classHandle = getClassHandle();
  } catch (e) {
    console.log('RN_REPLAY_WAIT reason=class-missing detail=' + e);
    return false;
  }

  const env = Java.vm.getEnv();
  const registerNativesPtr = getRegisterNativesPointer();
  const registerNatives = new NativeFunction(registerNativesPtr, 'int', ['pointer', 'pointer', 'pointer', 'int']);
  const methods = buildNativeMethodArray(rows, jiagu.base);
  const result = registerNatives(env.handle, classHandle, methods, rows.length);

  console.log('RN_REPLAY_RESULT class=' + TARGET_CLASS +
    ' count=' + rows.length +
    ' loader=' + resolvedLoaderDesc +
    ' jiaguBase=' + jiagu.base +
    ' registerNatives=' + registerNativesPtr +
    ' result=' + result);
  return true;
}

Java.perform(function () {
  let attempts = 0;
  const timer = setInterval(function () {
    attempts++;
    try {
      const done = replayOnce();
      if (done || attempts >= MAX_ATTEMPTS) {
        if (!done) {
          console.log('RN_REPLAY_GIVEUP attempts=' + attempts);
        }
        clearInterval(timer);
      }
    } catch (e) {
      console.log('RN_REPLAY_ERROR attempt=' + attempts + ' error=' + e + ' stack=' + e.stack);
      clearInterval(timer);
    }
  }, ATTEMPT_INTERVAL_MS);
});
