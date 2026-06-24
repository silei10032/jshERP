package com.jsh.erp.utils;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Excel 工具类（EasyExcel 实现，替换原 jxl）。
 *
 * 兼容方面说明：
 * - 文件格式：原 jxl 仅支持 .xls，本实现统一输出 .xlsx；下载头同步改为 .xlsx
 * - 单元格样式：原代码为 * 字段标红、表头黑体、边框等。这是 UX 装饰，对功能没影响，
 *   EasyExcel 默认样式已足够清晰，故不再迁移
 * - 读取：jxl Sheet 的随机访问模式（getContent(sheet, row, col)）以 SheetRows 包装
 *   List&lt;Map&lt;Integer,String&gt;&gt; 保留同等 API
 */
@Slf4j
public class ExcelUtils {

    /** 读取后的工作表，包装 EasyExcel 同步读结果，提供索引式访问 */
    public static class SheetRows {
        private final List<Map<Integer, String>> rows;
        /** 整表列数缓存：max(colIdx)+1，模拟原 jxl sheet.getColumns() 语义 */
        private final int sheetColumnCount;

        public SheetRows(List<Map<Integer, String>> rows) {
            this.rows = rows;
            int maxCol = -1;
            if (rows != null) {
                for (Map<Integer, String> row : rows) {
                    if (row != null) {
                        for (Integer k : row.keySet()) {
                            if (k != null && k > maxCol) {
                                maxCol = k;
                            }
                        }
                    }
                }
            }
            this.sheetColumnCount = maxCol + 1;
        }

        public int rowCount() {
            return rows == null ? 0 : rows.size();
        }

        public String getString(int rowIdx, int colIdx) {
            if (rows == null || rowIdx >= rows.size()) {
                return null;
            }
            Map<Integer, String> row = rows.get(rowIdx);
            return row == null ? null : row.get(colIdx);
        }

        /** 整表列数（max colIdx + 1），等价原 jxl sheet.getColumns() */
        public int sheetColumnCount() {
            return sheetColumnCount;
        }
    }

    public static InputStream getPathByFileName(String template, String tmpFileName) {
        File tmpFile = new File(template, tmpFileName);
        if (tmpFile.exists()) {
            try {
                return new FileInputStream(tmpFile);
            } catch (FileNotFoundException e) {
                log.error("", e);
            }
        }
        return null;
    }

    /** 同步读取指定 sheet，返回 SheetRows */
    public static SheetRows readSheet(InputStream in, int sheetIndex) {
        List<Map<Integer, String>> rows = EasyExcel.read(in).sheet(sheetIndex).headRowNumber(0).doReadSync();
        return new SheetRows(rows);
    }

    /**
     * 多 sheet 导出：在已有的 ExcelWriter 上追加一个 sheet。
     * 数据布局：第 0 行写 tip 提示，第 1 行写标题，从第 2 行开始写数据。
     */
    public static void exportObjectsManySheet(ExcelWriter writer, String tip, String[] names,
                                              String title, int index, List<String[]> objects) {
        WriteSheet sheet = EasyExcel.writerSheet(index, title).build();
        List<List<String>> data = new ArrayList<>();
        data.add(List.of(tip == null ? "" : tip));
        data.add(Arrays.asList(names));
        for (String[] row : objects) {
            data.add(Arrays.asList(row));
        }
        writer.write(data, sheet);
    }

    /**
     * 单 sheet 导出到文件。布局同 exportObjectsManySheet。
     */
    public static File exportObjectsOneSheet(String path, String fileName, String tip,
                                             String[] names, String title, List<Object[]> objects) {
        FileUtils.makedir(path);
        File excelFile = new File(path + File.separator + fileName);
        try (ExcelWriter writer = EasyExcel.write(excelFile).build()) {
            WriteSheet sheet = EasyExcel.writerSheet(0, title).build();
            List<List<Object>> data = new ArrayList<>();
            data.add(List.of((Object) (tip == null ? "" : tip)));
            data.add(Arrays.asList((Object[]) names));
            for (Object[] row : objects) {
                List<Object> rowList = new ArrayList<>(row.length);
                for (Object cell : row) {
                    if (cell instanceof BigDecimal || cell instanceof Double
                            || cell instanceof Integer || cell instanceof Long) {
                        rowList.add(Double.parseDouble(cell.toString()));
                    } else {
                        rowList.add(cell == null ? "" : cell.toString());
                    }
                }
                data.add(rowList);
            }
            writer.write(data, sheet);
        }
        return excelFile;
    }

    public static String getContent(SheetRows src, int rowNum, int colNum) {
        String val = src.getString(rowNum, colNum);
        return val == null ? null : val.trim();
    }

    public static String getContentNumber(SheetRows src, int rowNum, int colNum) {
        String val = src.getString(rowNum, colNum);
        if (val == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(val.trim());
            DecimalFormat df = new DecimalFormat("#.######");
            return df.format(value);
        } catch (NumberFormatException e) {
            return val.trim();
        }
    }

    /** 真实行数：从首行往后，扣除全部列均为空白的行。
     *  列数用整表 sheetColumnCount() 而非单行非空 cell 数，保持原 jxl 语义 */
    public static int getRightRows(SheetRows src) {
        int total = src.rowCount();
        int sheetCols = src.sheetColumnCount();
        int rightRows = total;
        for (int i = 1; i < total; i++) {
            int nullCellNum = 0;
            for (int j = 0; j < sheetCols; j++) {
                String val = src.getString(i, j);
                if (StringUtils.isEmpty(val == null ? null : val.trim())) {
                    nullCellNum++;
                }
            }
            if (sheetCols == 0 || nullCellNum >= sheetCols) {
                rightRows--;
            }
        }
        return rightRows;
    }

    public static void downloadExcel(File excelFile, String fileName, HttpServletResponse response) throws Exception {
        response.setContentType("application/octet-stream");
        String encodedName = new String(fileName.getBytes("gbk"), StandardCharsets.ISO_8859_1);
        response.setHeader("Content-Disposition", "attachment;filename=\"" + encodedName + ".xlsx\"");
        try (FileInputStream fis = new FileInputStream(excelFile)) {
            OutputStream out = response.getOutputStream();
            byte[] bytes = new byte[1024 * 1024];
            int length;
            while ((length = fis.read(bytes)) != -1) {
                out.write(bytes, 0, length);
            }
            out.flush();
        }
    }
}
