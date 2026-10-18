import pino from "pino";

const logger = pino();

interface Options {
  id: string;
}

class Service {
  @Bookkeeping
  skipped: number = 0;

  run(options: Options): number {
    if (!options.id) {
      return 0;
    }
    logger.info("run", options.id);
    if (options.id.length === 0) {
      throw new Error("bad");
    }
    console.log("done");
    return options.id.length;
  }

  bump(): void {
    this.skipped++;
  }
}

const message = format("x");
console.log(message);

function format(value: string): string {
  return `value:${value}`;
}
