<template>
  <div>{{ message }}</div>
</template>

<script setup lang="ts">
import { computed } from "vue";

const message = computed(() => "hello");
const logger = createLogger();

function work(id: string) {
  logger.info("work", id);
  if (!id) {
    return;
  }
  console.log("done", message.value);
  throw new Error("bad");
}
</script>
