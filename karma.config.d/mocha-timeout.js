config.set({
  client: {
    mocha: {
      // Browser contracts can cross asynchronous storage, Gateway, and Wasm
      // startup boundaries. Keep them bounded without inheriting Mocha's 2s
      // unit-test default on shared CI runners.
      timeout: 20_000,
    },
  },
});
