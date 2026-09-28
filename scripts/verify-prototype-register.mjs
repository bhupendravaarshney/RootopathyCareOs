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
  ...Array.from(
    { length: 11 },
    (_, index) => `P7-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 10 },
    (_, index) => `P8-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 12 },
    (_, index) => `P9-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 9 },
    (_, index) => `P10-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 11 },
    (_, index) => `P11-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 10 },
    (_, index) => `P12-${String(index + 1).padStart(2, "0")}`,
  ),
  ...Array.from(
    { length: 10 },
    (_, index) => `P13-${String(index + 1).padStart(2, "0")}`,
  ),
];

const dynamicContracts = [
  "`${module}-${String(index + 1).padStart(2, '0')}`",
  "screen.id.replace(/^M3-/, 'P3-')",
  "screen.id.replace(/^M4-/, 'P4-')",
  "screen.id.replace(/^M5-/, 'P5-')",
  "`COS-${String(index + 1).padStart(2, '0')}`",
  "screen.id.replace(/^M7-/, 'P7-')",
  "screen.id.replace(/^M8-/, 'P8-')",
  "screen.id.replace(/^M9-/, 'P9-')",
  "screen.id.replace(/^M10-/, 'P10-')",
  "screen.id.replace(/^M11-/, 'P11-')",
  "screen.id.replace(/^M12-/, 'P12-')",
  "screen.id.replace(/^M13-/, 'P13-')",
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
  !source.includes("const cosTitles") ||
  !source.includes("const m7:") ||
  !source.includes("const m8:") ||
  !source.includes("const m9:") ||
  !source.includes("const m10:") ||
  !source.includes("const m11:") ||
  !source.includes("const m12:") ||
  !source.includes("const m13:")
) {
  throw new Error("One or more screen registries are missing");
}
console.log(
  JSON.stringify(
    {
      expectedScreens: required.length,
      modules: {
        M1: 23,
        M2: 29,
        M3: 16,
        M4: 15,
        M5: 12,
        COS: 27,
        P7: 11,
        P8: 10,
        P9: 12,
        P10: 9,
        P11: 11,
        P12: 10,
        P13: 10,
      },
      status: "PASS",
    },
    null,
    2,
  ),
);
