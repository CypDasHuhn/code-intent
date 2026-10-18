import pino from "pino";

const logger = pino();

// scp:bookkeeping
let skipped = 0;

class Service {
  doWork(id) {
    logger.info("starting", id);
    const value = compute(id);
    if (value < 0) {
      logger.error("negative", id);
      throw new Error("bad");
    }
    if (values.isEmpty()) {
      return;
    }
    console.log("done", value);
    return value;
  }
}

function compute(id) {
  assert(id, "id required");
  const message = format(id);
  console.log(message);
  return id.length;
}

function format(id) {
  return `id:${id}`;
}

function count() {
  skipped++;
}
