/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wipro.ats.bdre.im.etl.api;

import com.wipro.ats.bdre.IMConfig;
import com.wipro.ats.bdre.im.etl.api.base.ETLBase;
import com.wipro.ats.bdre.im.etl.api.exception.ETLException;
import org.apache.commons.cli.CommandLine;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by vishnu on 12/17/14.
 * Modified by arijit
 */
public class BaseLoad extends ETLBase {

    private static final Logger LOGGER = Logger.getLogger(BaseLoad.class);
    private static final String[][] PARAMS_STRUCTURE = {
            {"p", "process-id", " Process id of ETLDriver"},
            {"instExecId", "instance-exec-id", " Process id of ETLDriver"}
    };


    public void execute(String[] params) {

        CommandLine commandLine = getCommandLine(params, PARAMS_STRUCTURE);

        String processId = commandLine.getOptionValue("process-id");
        String instanceExecId = commandLine.getOptionValue("instance-exec-id");
        init(processId);
        //Getting core table information
        String baseTableName = getBaseTable().getTableName();
        String baseTableDbName = getBaseTable().getDbName();
        String baseTableDdl = getBaseTable().getDdl();

        processStage(baseTableDbName, baseTableName, instanceExecId, baseTableDdl);
    }


    private void processStage(String dbName, String baseTableName, String instanceExecId, String ddl) {
        try {

            Configuration conf = new Configuration();
            conf.set("fs.defaultFS", IMConfig.getProperty("common.default-fs-name"));
            FileSystem fs = FileSystem.get(conf);
            String stageTableName =  baseTableName + "_" + instanceExecId;
            //Stage table is the source and base table is the destination
            Table stageTable=getMetaStoreClient().getTable(dbName,stageTableName);
            Table baseTable=getMetaStoreClient().getTable(dbName,baseTableName);
            List<Partition> stagePartitions = getMetaStoreClient().listPartitions(dbName, stageTableName, (short) -1);
            List<Partition> basePartitions = new ArrayList<Partition>();

            String stageTableLocation = stageTable.getSd().getLocation();
            String baseTableLocation = baseTable.getSd().getLocation();
            for (Partition stagePartition : stagePartitions)
            {
                //Create a copy of the stage partitions and change the table name to that of base
                //Change the location also other wise the added partition will point inside the stage table
                Partition partition =stagePartition.deepCopy();
                partition.setTableName(baseTableName);
                String relativePartitionPath=partition.getSd().getLocation().replace(stageTableLocation,"");
                partition.getSd().setLocation(baseTableLocation+relativePartitionPath);
                basePartitions.add(partition);
                Path srcPath=new Path(stageTableLocation + relativePartitionPath);
                Path destPath=new Path(baseTableLocation + relativePartitionPath);
                LOGGER.debug("Will move partitions from " + srcPath + " to "+destPath.getParent());
                //if the parent destination directory(upper level partition) does not exist create it
                fs.mkdirs(destPath.getParent());
                //Now do the rename
                fs.rename(srcPath,destPath.getParent());

            }
            //add partitions in the core table.
            getMetaStoreClient().add_partitions(basePartitions);

            LOGGER.debug("Deleting stage table");
            getMetaStoreClient().dropTable(dbName,stageTableName);
            LOGGER.info("BaseLoad completed for " + baseTableName);
        } catch (Exception e) {
            LOGGER.error("Exception" + e);
            throw new ETLException(e);
        }
    }

}