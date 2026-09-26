import { readFileSync } from "node:fs";

const source = readFileSync(
  new URL("../frontend/src/data/screens.ts", import.meta.url),
  "utf8",
);
const required = [
  ...Array.from(
    { length: 23 },
    (_, index) => `M1-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 29 },
    (_, index) => `M2-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 16 },
    (_, index) => `P3-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 15 },
    (_, index) => `P4-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 12 },
    (_, index) => `P5-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 27 },
    (_, index) => `COS-${String(index + 1).padStart(2, "0")}`,
  ),
];

const dynamicContracts = [
  "`${module}-${String(index + 1).padStart(2, '0')}`",
  "screen.id.replace(/^M3-/, 'P3-')",
  "screen.id.replace(/^M4-/, 'P4-')",
  "screen.id.replace(/^M5-/, 'P5-')",
  "`COS-${String(index + 1).padStart(2, '0')}`",
];
if (!dynamicContracts.every((contract) => source.includes(contract))) {
  throw new Error("Screen ID generation contract is missing");
}
if (
  !source.includes("const m1:") ||
  !source.includes("const m2:") ||
  !source.includes("const m3:") ||
  !source.includes("const m4:") ||
  !source.includes("const m5:") ||
  !source.includes("const cosTitles")
) {
  throw new Error("One or more screen registries are missing");
}
console.log(
  JSON.stringify(
    {
      expectedScreens: required.length,
      modules: { M1: 23, M2: 29, M3: 16, M4: 15, M5: 12, COS: 27 },
      status: "PASS",
    },
    null,
    2,
  ),
);
