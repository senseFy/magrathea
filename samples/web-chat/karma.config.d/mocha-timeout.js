config.set({
  client: {
    mocha: {
      // These tests cross a real browser/HTTP/Gateway boundary and include bounded
      // readiness, stream-resume, and cancellation waits. Mocha's 2s unit-test
      // default is too short for Wasm startup on shared CI runners.
      timeout: 20_000,
    },
  },
});
