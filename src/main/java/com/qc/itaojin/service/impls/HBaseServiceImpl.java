package com.qc.itaojin.service.impls;

import com.qc.itaojin.annotation.HBaseColumn;
import com.qc.itaojin.annotation.HBaseEntity;
import com.qc.itaojin.annotation.HBaseFamily;
import com.qc.itaojin.common.HBaseConstants;
import com.qc.itaojin.common.HBaseErrorCode;
import com.qc.itaojin.exception.ItaojinHBaseException;
import com.qc.itaojin.service.IHBaseService;
import com.qc.itaojin.service.common.HBaseBaseServiceImpl;
import com.qc.itaojin.util.ReflectUtils;
import com.qc.itaojin.util.ReflectionUtils;
import com.qc.itaojin.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.util.Assert;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @desc
 * @author fuqinqin
 * @date 2018-07-14
 */
@Slf4j
public class HBaseServiceImpl extends HBaseBaseServiceImpl implements IHBaseService {

    @Override
    public void updateVersions(String nameSpace, String table, String family, int versions) throws ItaojinHBaseException {
        Assert.hasText(nameSpace, "nameSpace must not be null");
        Assert.hasText(table, "table must not be null");
        Assert.hasText(family, "family must not be null");
        if(versions <= 0){
            throw new IllegalArgumentException("versions must larger than 0");
        }

        table = StringUtils.contact(nameSpace, ":", table);
        Connection connection = null;
        Admin admin = null;
        try {
            connection = getConn();
            admin = connection.getAdmin();
            TableName tableName = TableName.valueOf(table);
            if(admin.tableExists(tableName)){
                HColumnDescriptor hColumnDescriptor = new HColumnDescriptor(family);
                hColumnDescriptor.setVersions(versions, versions);
                admin.modifyColumn(tableName, hColumnDescriptor);
                log.info("updateVersions success, tables={}", table);
            }else{
                log.info("HBase table {} is not existed", table);
                throw new ItaojinHBaseException(HBaseErrorCode.UPDATE_VERSIONS_FAILED, table,
                        StringUtils.contact("HBase table {} is not existed", table));
            }
        } catch (IOException e) {
            throwException(HBaseErrorCode.UPDATE_VERSIONS_FAILED, e, table);
        } finally {
            if(!useSingleConn.get()){
                closeConn(connection);
                closeAdmin(admin);
            }
        }
    }

    @Override
    public void update(String nameSpace, String table, String rowKey, Map<String, String> columns) throws ItaojinHBaseException {
        Assert.hasText(nameSpace, "nameSpace must not be null");
        Assert.hasText(table, "table must not be null");
        Assert.hasText(rowKey, "rowKey must not be null");
        Assert.notEmpty(columns, "columns must not be empty");

        String tableName = StringUtils.contact(nameSpace, ":", table);

        HTable hTable = null;
        try {
            hTable = getHTable(tableName);
            for(Map.Entry<String, String> entry : columns.entrySet()){
                Put put = new Put(toBytes(rowKey));
                String columnName = entry.getKey();
                String columnValue = entry.getValue();
                put.addColumn(toBytes(HBaseConstants.DEFAULT_FAMILY), toBytes(columnName), toBytes(columnValue));
                hTable.put(put);
            }
        } catch (IOException e) {
            throwException(HBaseErrorCode.UPDATE_FAILED, e, tableName);
        } finally {
            closeHTable(hTable);
        }
    }

    @Override
    public void delete(String nameSpace, String table, String rowKey) throws ItaojinHBaseException {
        Assert.hasText(nameSpace, "nameSpace must not be null");
        Assert.hasText(table, "table must not be null");
        Assert.hasText(rowKey, "rowKey must not be null");

        String tableName = StringUtils.contact(nameSpace, ":", table);
        HTable hTable = null;
        try {
            hTable = getHTable(tableName);
            List<Delete> list = new ArrayList<>();
            Delete delete = new Delete(toBytes(rowKey));
            list.add(delete);

            hTable.delete(list);
            log.info("delete success");
        } catch (IOException e) {
            throwException(HBaseErrorCode.DELETE_FAILED, e, tableName);
        } finally {
            closeHTable(hTable);
        }
    }

    @Override
    public <T> List<T> scanAll(Class<T> clazz) throws ItaojinHBaseException {
        Assert.notNull(clazz, "clazz must not be null");
        ReflectUtils.isAnnotationPresent(clazz, HBaseEntity.class);

        // analyze table name
        String tableName = ReflectUtils.analyzeClassAnnotation(clazz, HBaseEntity.class, "table");

        // 是否统一配置HBaseFamily
        boolean isFamilyUnifiedConfiguration = false;

        /**
         *  analyze family name.
         *  if HBaseFamily annotation is used on Class, the HBaseFamily annotation used on
         *  field will be invalid.
         * */
        String familyName = ReflectUtils.analyzeClassAnnotation(clazz, HBaseFamily.class, "value");

        if(StringUtils.isNotBlank(familyName)){
            isFamilyUnifiedConfiguration = true;
        }


        HTable hTable = null;
        ResultScanner resultScanner = null;

        try{
            hTable = getHTable(tableName);
            log.info("hbase table is {}", tableName);
            Scan scan = new Scan();
            resultScanner = hTable.getScanner(scan);

            if(resultScanner == null){
                return null;
            }

            // get all fields
            Field[] fields = clazz.getDeclaredFields();
            List<T> list = new ArrayList<>();
            for (Result result : resultScanner) {
                T t = clazz.newInstance();
                for (Field field : fields) {
                    // family name
                    if(!isFamilyUnifiedConfiguration){
                        familyName = ReflectUtils.analyzeFieldAnnotation(field, HBaseFamily.class, "value");
                        if(StringUtils.isBlank(familyName)){
                            familyName = HBaseConstants.DEFAULT_FAMILY;
                        }
                    }

                    // field name
                    String fieldName = ReflectUtils.analyzeFieldAnnotation(field, HBaseColumn.class, "value");
                    if(StringUtils.isBlank(fieldName)){
                        fieldName = StringUtils.converseToHump(field.getName());
                    }

                    // set method name
                    String setMethodName = ReflectUtils.buildSet(field.getName());

                    byte[] bys = result.getValue(Bytes.toBytes(familyName), Bytes.toBytes(fieldName));
                    if(bys == null){
                        continue;
                    }

                    String value = new String(bys);
                    Method method = ReflectionUtils.findMethod(clazz, setMethodName, ReflectUtils.mapClass(field.getType()));
                    method.invoke(t, ReflectUtils.transValue(field, value));
                }
                list.add(t);
            }

            return list;
        } catch (Exception e) {
            throwException(HBaseErrorCode.UPDATE_FAILED, e, tableName);
        } finally {
            closeHTable(hTable);
            closeResultScanner(resultScanner);
        }

        return null;
    }
}
