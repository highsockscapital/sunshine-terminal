#!/usr/bin/env node
import { main } from '../src/index.js';

main().catch((err) => {
  console.error('Mon dieu! An error occurred:', err);
  process.exit(1);
});
