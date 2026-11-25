package sodaviewer;

import io.jhdf.HdfFile;
import io.jhdf.api.Dataset;
import io.jhdf.api.Group;
import io.jhdf.api.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read SODA dense embeddings: {@code /data/matrix} + row_ids + col_ids. */
public final class EmbeddingData {
    public final String[] cellIds;
    public final String[] dimNames;
    /** cells × dims (always oriented for plotting). */
    public final double[][] xy;

    private EmbeddingData(String[] cellIds, String[] dimNames, double[][] xy) {
        this.cellIds = cellIds;
        this.dimNames = dimNames;
        this.xy = xy;
    }

    public static EmbeddingData load(Path h5Path) {
        try (HdfFile h5 = new HdfFile(h5Path)) {
            Group data = childGroup(h5, "data");
            if (data == null) {
                data = h5;
            }
            Dataset matrixDs = childDataset(data, "matrix");
            Dataset rowDs = childDataset(data, "row_ids");
            Dataset colDs = childDataset(data, "col_ids");
            if (matrixDs == null || rowDs == null || colDs == null) {
                throw new IllegalArgumentException("Missing data/matrix|row_ids|col_ids in " + h5Path);
            }
            String[] rows = readStringVector(rowDs);
            String[] cols = readStringVector(colDs);
            double[][] mat = readMatrix2d(matrixDs);
            // Orient to cells × dims (match iCellPy / iCellR2 contract)
            if (mat.length == cols.length && mat[0].length == rows.length
                    && !(mat.length == rows.length && mat[0].length == cols.length)) {
                mat = transpose(mat);
            } else if (mat.length != rows.length && mat[0].length == rows.length) {
                mat = transpose(mat);
            }
            int n = Math.min(mat.length, rows.length);
            int d = Math.min(mat[0].length, 2);
            double[][] xy = new double[n][2];
            for (int i = 0; i < n; i++) {
                xy[i][0] = mat[i][0];
                xy[i][1] = d > 1 ? mat[i][1] : 0.0;
            }
            String[] cellIds = new String[n];
            System.arraycopy(rows, 0, cellIds, 0, n);
            return new EmbeddingData(cellIds, cols, xy);
        }
    }

    private static Group childGroup(Group parent, String name) {
        Node n = parent.getChild(name);
        return n instanceof Group g ? g : null;
    }

    private static Dataset childDataset(Group parent, String name) {
        Node n = parent.getChild(name);
        return n instanceof Dataset d ? d : null;
    }

    private static String[] readStringVector(Dataset ds) {
        Object raw = ds.getData();
        if (raw instanceof String[] s) {
            return s;
        }
        if (raw instanceof Object[] arr) {
            String[] out = new String[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = String.valueOf(arr[i]);
            }
            return out;
        }
        throw new IllegalArgumentException("Unsupported string dataset: " + raw.getClass());
    }

    private static double[][] readMatrix2d(Dataset ds) {
        Object raw = ds.getData();
        if (raw instanceof double[][] m) {
            return m;
        }
        if (raw instanceof float[][] m) {
            double[][] out = new double[m.length][m[0].length];
            for (int i = 0; i < m.length; i++) {
                for (int j = 0; j < m[0].length; j++) {
                    out[i][j] = m[i][j];
                }
            }
            return out;
        }
        if (raw instanceof double[] flat) {
            int[] shape = ds.getDimensions();
            if (shape.length != 2) {
                throw new IllegalArgumentException("Expected 2-D matrix");
            }
            int nr = shape[0];
            int nc = shape[1];
            double[][] out = new double[nr][nc];
            for (int i = 0; i < nr; i++) {
                System.arraycopy(flat, i * nc, out[i], 0, nc);
            }
            return out;
        }
        if (raw instanceof float[] flat) {
            int[] shape = ds.getDimensions();
            int nr = shape[0];
            int nc = shape[1];
            double[][] out = new double[nr][nc];
            for (int i = 0; i < nr; i++) {
                for (int j = 0; j < nc; j++) {
                    out[i][j] = flat[i * nc + j];
                }
            }
            return out;
        }
        throw new IllegalArgumentException("Unsupported matrix type: " + raw.getClass());
    }

    private static double[][] transpose(double[][] m) {
        int nr = m.length;
        int nc = m[0].length;
        double[][] t = new double[nc][nr];
        for (int i = 0; i < nr; i++) {
            for (int j = 0; j < nc; j++) {
                t[j][i] = m[i][j];
            }
        }
        return t;
    }

    public List<String> dimLabels() {
        List<String> out = new ArrayList<>();
        for (String c : dimNames) {
            out.add(c);
        }
        return out;
    }
}
