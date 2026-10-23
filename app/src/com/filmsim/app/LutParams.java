// From OpenMemoriesLUT by Pichuworks (MIT), see tools/LICENSE-OpenMemoriesLUT.txt
package com.filmsim.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 一组管线参数：1024 点伽马表（10bit int）+ 3×3 定点矩阵（×1024）。
 */
public class LutParams {
    public static final int KNOTS = 1024;
    public static final int MATRIX_SCALE = 1024;

    public int[] gamma = new int[KNOTS];   // 0..1023
    public int[] matrix = new int[9];      // 定点 ×1024

    public static LutParams identity() {
        LutParams p = new LutParams();
        for (int i = 0; i < KNOTS; i++) {
            p.gamma[i] = i;
        }
        p.matrix[0] = p.matrix[4] = p.matrix[8] = MATRIX_SCALE;
        return p;
    }

    /** 强度插值：percent=0 → 恒等；100 → 本参数。 */
    public LutParams withIntensity(int percent) {
        if (percent >= 100) {
            return this;
        }
        if (percent <= 0) {
            return identity();
        }
        double a = percent / 100.0;
        LutParams out = new LutParams();
        for (int i = 0; i < KNOTS; i++) {
            out.gamma[i] = (int) Math.round((1 - a) * i + a * gamma[i]);
        }
        for (int i = 0; i < 9; i++) {
            int id = (i == 0 || i == 4 || i == 8) ? MATRIX_SCALE : 0;
            out.matrix[i] = (int) Math.round((1 - a) * id + a * matrix[i]);
        }
        return out;
    }

    // ---------------- 缓存 ----------------

    public void save(File f) throws IOException {
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(f));
        try {
            dos.writeInt(1); // 版本
            for (int i = 0; i < KNOTS; i++) {
                dos.writeInt(gamma[i]);
            }
            for (int i = 0; i < 9; i++) {
                dos.writeInt(matrix[i]);
            }
        } finally {
            dos.close();
        }
    }

    public static LutParams load(File f) throws IOException {
        DataInputStream dis = new DataInputStream(new FileInputStream(f));
        try {
            if (dis.readInt() != 1) {
                throw new IOException("bad cache version");
            }
            LutParams p = new LutParams();
            for (int i = 0; i < KNOTS; i++) {
                p.gamma[i] = dis.readInt();
            }
            for (int i = 0; i < 9; i++) {
                p.matrix[i] = dis.readInt();
            }
            return p;
        } finally {
            dis.close();
        }
    }
}
