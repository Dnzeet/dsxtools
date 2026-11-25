package com.meteora.dstools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser minimal HID report descriptor. Hasilnya meniru struktur "collections"
 * dari WebHID (reportId + items[reportSize, reportCount]) dan dipakai untuk
 * menghitung ukuran report yang tepat sebelum dikirim ke kontroler.
 */
final class HidDescriptor {

    static final class Item {
        final int reportSize;
        final int reportCount;

        Item(int reportSize, int reportCount) {
            this.reportSize = reportSize;
            this.reportCount = reportCount;
        }
    }

    static final class Report {
        final int id;
        final List<Item> items = new ArrayList<>();

        Report(int id) {
            this.id = id;
        }

        int bits() {
            int b = 0;
            for (Item i : items) b += i.reportSize * i.reportCount;
            return b;
        }
    }

    static final class Coll {
        int usagePage;
        int usage;
        final Map<Integer, Report> input = new LinkedHashMap<>();
        final Map<Integer, Report> output = new LinkedHashMap<>();
        final Map<Integer, Report> feature = new LinkedHashMap<>();
    }

    final List<Coll> collections = new ArrayList<>();
    boolean hasReportIds;

    static HidDescriptor parse(byte[] d, int len) {
        HidDescriptor h = new HidDescriptor();
        int usagePage = 0, reportSize = 0, reportCount = 0, reportId = 0;
        int[] stack = new int[64];
        int sp = 0;
        int localUsage = -1;
        int depth = 0;
        Coll cur = null;
        int i = 0;

        while (i < len) {
            int b = d[i] & 0xFF;
            if (b == 0xFE) { // long item: dilewati
                if (i + 1 >= len) break;
                i += 3 + (d[i + 1] & 0xFF);
                continue;
            }
            int size = b & 3;
            if (size == 3) size = 4;
            int type = (b >> 2) & 3;
            int tag = (b >> 4) & 0xF;
            if (i + 1 + size > len) break;

            int val = 0;
            for (int k = 0; k < size; k++) val |= (d[i + 1 + k] & 0xFF) << (8 * k);
            i += 1 + size;

            if (type == 1) { // global
                switch (tag) {
                    case 0: usagePage = val; break;
                    case 7: reportSize = val; break;
                    case 8: reportId = val; h.hasReportIds = true; break;
                    case 9: reportCount = val; break;
                    case 10: // push
                        if (sp + 4 <= stack.length) {
                            stack[sp++] = usagePage;
                            stack[sp++] = reportSize;
                            stack[sp++] = reportCount;
                            stack[sp++] = reportId;
                        }
                        break;
                    case 11: // pop
                        if (sp >= 4) {
                            reportId = stack[--sp];
                            reportCount = stack[--sp];
                            reportSize = stack[--sp];
                            usagePage = stack[--sp];
                        }
                        break;
                    default: break;
                }
            } else if (type == 2) { // local
                if (tag == 0 && localUsage < 0) localUsage = val;
            } else if (type == 0) { // main
                switch (tag) {
                    case 0xA: // collection
                        if (depth == 0) {
                            cur = new Coll();
                            cur.usagePage = usagePage;
                            cur.usage = localUsage < 0 ? 0 : localUsage;
                            h.collections.add(cur);
                        }
                        depth++;
                        break;
                    case 0xC: // end collection
                        if (depth > 0) depth--;
                        break;
                    case 0x8: // input
                    case 0x9: // output
                    case 0xB: // feature
                        if (cur != null) {
                            Map<Integer, Report> m = tag == 0x8 ? cur.input : (tag == 0x9 ? cur.output : cur.feature);
                            Report r = m.get(reportId);
                            if (r == null) {
                                r = new Report(reportId);
                                m.put(reportId, r);
                            }
                            r.items.add(new Item(reportSize, reportCount));
                        }
                        break;
                    default: break;
                }
                localUsage = -1;
            }
        }
        return h;
    }

    /** Ukuran total report (termasuk byte report ID jika descriptor memakainya), atau -1 jika tidak dikenal. */
    int featureTotal(int id) {
        return total(2, id);
    }

    int outputTotal(int id) {
        return total(1, id);
    }

    private int total(int kind, int id) {
        for (Coll c : collections) {
            Map<Integer, Report> m = kind == 0 ? c.input : (kind == 1 ? c.output : c.feature);
            Report r = m.get(id);
            if (r != null) return (r.bits() + 7) / 8 + (hasReportIds ? 1 : 0);
        }
        return -1;
    }
}
