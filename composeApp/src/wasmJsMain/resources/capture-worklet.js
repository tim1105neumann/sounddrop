// SoundDrop microphone capture: batches the 128-frame render quanta of the first input
// channel into 2048-sample blocks and posts them to the main thread.
const BLOCK_SIZE = 2048;

class CaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.block = new Float32Array(BLOCK_SIZE);
    this.filled = 0;
    this.active = true;
    this.port.onmessage = (event) => {
      if (event.data === "stop") this.active = false;
    };
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) {
      let offset = 0;
      while (offset < channel.length) {
        const count = Math.min(channel.length - offset, BLOCK_SIZE - this.filled);
        this.block.set(channel.subarray(offset, offset + count), this.filled);
        this.filled += count;
        offset += count;
        if (this.filled === BLOCK_SIZE) {
          this.port.postMessage(this.block, [this.block.buffer]);
          this.block = new Float32Array(BLOCK_SIZE);
          this.filled = 0;
        }
      }
    }
    return this.active;
  }
}

registerProcessor("sounddrop-capture", CaptureProcessor);
