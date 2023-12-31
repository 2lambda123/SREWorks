package com.alibaba.sreworks.job.master.services;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alibaba.sreworks.job.master.common.JobTriggerType;
import com.alibaba.sreworks.job.master.domain.DO.*;
import com.alibaba.sreworks.job.master.domain.DTO.*;
import com.alibaba.sreworks.job.master.domain.repository.*;
import com.alibaba.sreworks.job.master.jobscene.JobSceneService;
import com.alibaba.sreworks.job.master.jobschedule.JobScheduleService;
import com.alibaba.sreworks.job.master.jobtrigger.JobTriggerService;
import com.alibaba.sreworks.job.master.params.*;
import com.alibaba.sreworks.job.utils.JsonUtil;
import com.alibaba.sreworks.job.utils.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static java.util.stream.DoubleStream.builder;

@Slf4j
@Service
public class StreamJobService {

    @Autowired
    SreworksStreamJobRepository streamJobRepository;

    @Autowired
    SreworksStreamJobBlockRepository sreworksStreamJobBlockRepository;

    @Autowired
    SreworksStreamJobRuntimeRepository sreworksStreamJobRuntimeRepository;

    @Autowired
    VvpService vvpService;

    @Autowired
    StreamJobBlockService streamJobBlockService;

    public Page<SreworksStreamJobDTO> gets(Pageable pageable) throws Exception {
        Page<SreworksStreamJob> jobs = streamJobRepository.findAll(pageable);

        return jobs.map(streamJob -> {
            SreworksStreamJobDTO jobDTO = new SreworksStreamJobDTO(streamJob);
            return jobDTO;
        });
    }

    public SreworksStreamJobDTO get(Long streamJobId) throws Exception {
        SreworksStreamJob job = streamJobRepository.findFirstById(streamJobId);
        return new SreworksStreamJobDTO(job);
    }

    public SreworksStreamJobDTO getByName(String name) throws Exception {
        SreworksStreamJob job = streamJobRepository.findFirstByName(name);
        return new SreworksStreamJobDTO(job);
    }

    public void delete(Long streamJobId) {
        streamJobRepository.deleteById(streamJobId);
    }

    public String pythonTemplate(Long streamJobId) throws Exception {
        List<SreworksStreamJobBlockDTO> blocks = streamJobBlockService.listByStreamJobId(streamJobId);
        JSONObject sourceBlock = null;
        JSONObject sinkBlock = null;
        String templateContent;
        for (SreworksStreamJobBlockDTO block : blocks) {
            if(StringUtils.equals(block.getBlockType(), "source") && sourceBlock == null){
                sourceBlock = block.getData();
                sourceBlock.put("name", block.getName());
            }else if(StringUtils.equals(block.getBlockType(), "sink") && sinkBlock == null){
                sinkBlock = block.getData();
                sinkBlock.put("name", block.getName());
            }
        }

        if(sourceBlock != null && sinkBlock != null){
            templateContent = "";
            templateContent += "t_env.from_path('" + sourceBlock.getString("name") + "').select(\n";
            JSONArray columns = sourceBlock.getJSONArray("columns");
            if(columns != null){
                for (int i = 0; i < columns.size(); i++) {
                    JSONObject column = columns.getJSONObject(i);
                    String columnName = column.getString("columnName");
                    if (columnName != null){
                        templateContent += "    col('" + columnName + "')";
                        if (i == columns.size() - 1){
                            templateContent += "\n";
                        } else {
                            templateContent += ",\n";
                        }
                    }
                }
            }
            templateContent += ").execute_insert('" + sinkBlock.getString("name") +  "').print()\n";
        }else{
            templateContent = StringUtil.readResourceFile("python-default.py");
        }

        return templateContent;
    }

    public SreworksStreamJobDTO create(StreamJobCreateParam param) throws Exception {
        SreworksStreamJob job = param.job();
        job.setStatus("CANCELLED");
        JSONObject options;
        if (StringUtils.isEmpty(job.getOptions())){
            options = new JSONObject();
        } else {
            options = JSONObject.parseObject(job.getOptions());
        }
        if (StringUtils.isEmpty(options.getString("template"))){
            String templateContent = StringUtil.readResourceFile("pyflink-template.py");
            options.put("template", templateContent);
        }
        if (options.getJSONObject("settings") == null){
            options.put("settings", JsonUtil.map(
                    "resources", JsonUtil.map(
                            "jobmanager", JsonUtil.map(
                                    "cpu", 1,
                                    "memory","4G"
                            ),
                            "taskmanager", JsonUtil.map(
                                    "cpu", 1,
                                    "memory","4G"
                            )
                    ),
                    "parallelism", 1,
                    "numberOfTaskManagers", 1,
                    "pythonRuntimeId", 2
            ));
        }
        job.setOptions(options.toJSONString());
        job = streamJobRepository.saveAndFlush(job);
        return new SreworksStreamJobDTO(job);
    }

    private SreworksStreamJob convertDO(SreworksStreamJobDTO jobDTO){
        return SreworksStreamJob.builder()
                .id(jobDTO.getId())
                .gmtCreate(System.currentTimeMillis())
                .gmtModified(System.currentTimeMillis())
                .creator(jobDTO.getCreator())
                .operator(jobDTO.getOperator())
                .status(jobDTO.getStatus())
                .appId(jobDTO.getAppId())
                .name(jobDTO.getName())
                .alias(jobDTO.getAlias())
                .tags(jobDTO.getTags().toJSONString())
                .description(jobDTO.getDescription())
                .options(jobDTO.getOptions().toJSONString())
                .jobType(jobDTO.getJobType())
                .build();
    }

    public SreworksStreamJobDTO updateSettings(SreworksStreamJobDTO job, JSONObject settings) throws Exception {
        JSONObject options = job.getOptions();
        options.put("settings", settings);
        job.setOptions(options);

        SreworksStreamJob updateJob = streamJobRepository.saveAndFlush(convertDO(job));
        return new SreworksStreamJobDTO(updateJob);
    }

    public SreworksStreamJobDTO updateTemplate(SreworksStreamJobDTO job, String templateContent){
        JSONObject options = job.getOptions();
        options.put("template", templateContent);
        job.setOptions(options);

        log.info("options {}", options.toJSONString());

        SreworksStreamJob updateJob = streamJobRepository.saveAndFlush(convertDO(job));
        return new SreworksStreamJobDTO(updateJob);
    }

    public SreworksStreamJobDTO updateStatus(SreworksStreamJobDTO job, String status){
        job.setStatus(status);
        SreworksStreamJob updateJob = streamJobRepository.saveAndFlush(convertDO(job));
        return new SreworksStreamJobDTO(updateJob);
    }

    private JSONArray generateTable(StringBuilder scriptContent, SreworksStreamJobBlockDTO block){
        
            /**
             *   t_env.execute_sql("""
             *    CREATE TABLE my_sink (
             *      a INT,
             *      b STRING
             *    ) WITH (
             *       'connector' = 'jdbc',
             *       'lookup.cache.max-rows' = '1000',
             *       'lookup.cache.ttl' = '600s',
             *       'password' = '***',
             *       'table-name' = 'datagen',
             *       'url' = 'jdbc:mysql://**:3306/test_db?useUnicode=true&characterEncoding=utf-8&useSSL=false',
             *       'username' = 'root'
             *     );
             *   """)
             * 
             *
             */
            
        scriptContent.append("t_env.execute_sql(f\"\"\"\n");
        scriptContent.append("    CREATE TABLE " + block.getName() +" (\n");
        JSONArray columns = block.getData().getJSONArray("columns");
        if(columns != null){
            for (int i = 0; i < columns.size(); i++) {
                JSONObject column = columns.getJSONObject(i);
                String columnName = column.getString("columnName");
                String columnType = column.getString("columnType");
                if (StringUtils.isNotBlank(columnType)){
                    if (StringUtils.isNotBlank(columnName)){
                        scriptContent.append("      `" + columnName + "` " + columnType);
                    } else {
                        scriptContent.append("      " + columnType);
                    }
                    if (i == columns.size() - 1){
                        scriptContent.append("\n");
                    } else {
                        scriptContent.append(",\n");
                    }
                }
            }
        }

        scriptContent.append("    ) WITH (\n");
        JSONArray options = block.getData().getJSONArray("options");
        if(options != null){
            for (int i = 0; i < options.size(); i++) {
                JSONObject option = options.getJSONObject(i);
                String key = option.getString("key");
                String value = option.getString("value");
                if (key != null && value != null){
                    scriptContent.append("      '" + key + "' = '" + value +"'");
                    if (i == options.size() - 1 ){
                        scriptContent.append("\n");
                    }else{
                        scriptContent.append(",\n");
                    }
                }
            }
        }
        scriptContent.append("    )\n");
        scriptContent.append("\"\"\")\n");
        return columns;
    }

    public String getScriptName(Long streamJobId) {
        return "streamjob-" + streamJobId.toString() + ".py";
    }

    public String getDeploymentName(Long streamJobId) {
        return "streamjob-" + streamJobId.toString();
    }

    public String generateScript(Long streamJobId, String templateContent) throws Exception {
        List<SreworksStreamJobBlockDTO>  blocks = streamJobBlockService.listByStreamJobId(streamJobId);
        StringBuilder sourceBlocks = new StringBuilder();
        StringBuilder sinkBlocks = new StringBuilder();
        StringBuilder pythonBlocks = new StringBuilder();
        for (SreworksStreamJobBlockDTO block : blocks){
            if(StringUtils.equals(block.getBlockType(), "source") || StringUtils.equals(block.getBlockType(), "sink")){

                sourceBlocks.append("\n# " + block.getBlockType() + " " + block.getName() + "\n");
                generateTable(sourceBlocks, block);

            }else if(StringUtils.equals(block.getBlockType(), "python")) {
                pythonBlocks.append("\n# python script " + block.getName() + "\n");
                pythonBlocks.append(block.getData().getString("content"));
                pythonBlocks.append("\n");
            }
        }

        Map<String, StringBuilder> blockTypes = new HashMap<>() {{
            put("# SOURCE_BLOCK #", sourceBlocks);
            put("# SINK_BLOCK #", sinkBlocks);
            put("# EXEC_BLOCK #", pythonBlocks);
        }};

        for (Map.Entry<String, StringBuilder> entry : blockTypes.entrySet()) {
            String blockType = entry.getKey();
            StringBuilder subBlocks = entry.getValue();
            templateContent = templateContent.replace(
                    blockType,
                    StringUtil.intendLines(
                            subBlocks,
                            StringUtil.getMatchLine(templateContent, blockType).replace(blockType, "")
                    ).toString()
            );
        }
        templateContent = "# "+ getScriptName(streamJobId) + "\n" + templateContent;

        return templateContent;
    }

    public JSONObject generateDeployment(StreamJobDeploymentParam param) throws Exception {
        StringBuilder mainArgs = new StringBuilder();
        String storePath = "s3://vvp/artifacts/namespaces/default/";
        JSONObject flinkConfiguration = new JSONObject();
        JSONObject artifact = new JSONObject();
        JSONObject spec = new JSONObject();
        JSONArray additionalDependencies = new JSONArray();
        ArrayList<String> jarFiles = new ArrayList<>();

        if (param.getAdditionalDependencies() != null){
            for (int i = 0; i < param.getAdditionalDependencies().size(); i++) {
                JSONObject item = param.getAdditionalDependencies().getJSONObject(i);
                String filePath = "";
                if(item.getString("filename") != null) {
                    filePath = storePath + item.getString("filename");
                } else if (item.getString("url") != null) {
                    filePath = item.getString("url");
                }
                if(StringUtils.isNotEmpty(filePath)) {
                    additionalDependencies.add(filePath);
                }
            }
        }

        if (param.getExtAdditionalDependencies() != null) {
            for (int i = 0; i < param.getExtAdditionalDependencies().size(); i++) {
                JSONObject item = param.getExtAdditionalDependencies().getJSONObject(i);
                if (item.getString("filename") != null && item.getString("filename").endsWith(".jar")) {
                    jarFiles.add(
                            "file:///flink/usrlib/" +
                                    item.getString("filename").substring(item.getString("filename").lastIndexOf("/") + 1)
                    );
                }
            }
        }

        spec.put("flinkConfiguration", flinkConfiguration);
        spec.put("artifact", artifact);
        if(param.getResources() != null){
            spec.put("resources", param.getResources());
        }
        JSONObject deployment = JsonUtil.map(
      "metadata", JsonUtil.map(
                 "name", param.getName()
              ),
             "spec",  JsonUtil.map(
             "deploymentTargetId", null,
                    "deploymentTargetName", "sreworksDeploymentTarget",
                    "sessionClusterName", null,
                    "template", JsonUtil.map(
                    "spec", spec
                    )
            )
        );
        artifact.put("kind", "JAR");

        // Flink 运行环境
        artifact.put("flinkVersion", param.getFlinkVersion());
        if(param.getFlinkImage() != null && param.getFlinkImage().indexOf(':') != -1){
            String[] imageRaws = param.getFlinkImage().split(":")[0].split("/");
            artifact.put("flinkImageTag", param.getFlinkImage().split(":")[1]);
            artifact.put("flinkImageRepository", imageRaws[imageRaws.length - 1]);
            artifact.put("flinkImageRegistry", param.getFlinkImage().substring(0, param.getFlinkImage().lastIndexOf("/")));
        }

        // pyflink运行环境
        artifact.put("jarUri", storePath + param.getJarUri());

        // Python venv 运行环境
        artifact.put("entryClass", param.getEntryClass());
        if(!StringUtils.isEmpty(param.getPyArchives())){
            mainArgs.append(" --pyArchives /flink/usrlib/" + param.getPyArchives());
            additionalDependencies.add(storePath + param.getPyArchives());
            if(param.getPyClientExecutable() != null){
                mainArgs.append(" --pyClientExecutable " + param.getPyArchives() + param.getPyClientExecutable());
                mainArgs.append(" --pyExecutable " + param.getPyArchives() + param.getPyClientExecutable());
            }
        }

        // 依赖jar包
        if(!jarFiles.isEmpty()){
            mainArgs.append(" --jarfile " + String.join(",", jarFiles));
        }

        // 主执行文件
        mainArgs.append(" --python /flink/usrlib/" + param.getPythonScriptName());
        additionalDependencies.add(storePath + param.getPythonScriptName());

        // 追加execParams参数到mainArgs
        if(param.getExecParams() != null && !param.getExecParams().isEmpty()){
            for (Map.Entry<String, String> kv : param.getExecParams().entrySet()) {
                mainArgs.append(" --"  + kv.getKey() + " " + kv.getValue());
            }
        }
        artifact.put("additionalDependencies", additionalDependencies);
        artifact.put("mainArgs", mainArgs.toString());
        return deployment;
    }

    public JSONObject generateDeploymentByStreamJob(SreworksStreamJobDTO job) throws Exception {
        JSONObject settings = job.getOptions().getJSONObject("settings");
        if(settings.getLong("pythonRuntimeId") == null) {
            return new JSONObject();
        }
        SreworksStreamJobRuntimeDTO runtime = runtimeGetById(settings.getLong("pythonRuntimeId"));
        JSONArray additionalDependencies = (JSONArray) runtime.getSettings().getJSONArray("additionalDependencies").clone();
        JSONArray execParamArray = settings.getJSONArray("execParams");

        Map<String, String> execParams = new HashMap<>();
        if(execParamArray  != null){
            for(int i = 0; i < execParamArray.size(); i++){
                JSONObject kv = execParamArray.getJSONObject(i);
                execParams.put(kv.getString("key"), kv.getString("value"));
            }
        }

        // 根据当前的connector来动态增加 additionalDependencies
        List<SreworksStreamJobBlockDTO> blocks = streamJobBlockService.listByStreamJobId(job.getId());
        HashSet<String> connectors = new HashSet<>();
        for (SreworksStreamJobBlockDTO block : blocks){
            if(StringUtils.equals(block.getBlockType(), "source")) {
                connectors.add(block.getData().getString("sourceType"));
            }else if(StringUtils.equals(block.getBlockType(), "sink")){
                connectors.add(block.getData().getString("sinkType"));
            }
        }
        for (String connectorName : connectors) {
            FlinkConnectorDTO connector = vvpService.getConnector(connectorName);
            for (int i = 0; i < connector.getDependencies().size(); i++) {
                String dependency = connector.getDependencies().getString(i).replace(
                        "/vvp/sql/opt/connectors",
                        "http://prod-job-job-master.sreworks/stream-job/connector/resource");
                additionalDependencies.add(JsonUtil.map("url", dependency));
            }
        }

        StreamJobDeploymentParam deploymentParam = StreamJobDeploymentParam.builder()
                .name(getDeploymentName(job.getId()))
                .entryClass(runtime.getSettings().getString("entryClass"))
                .jarUri(runtime.getSettings().getString("jarUri"))
                .flinkVersion(runtime.getSettings().getString("flinkVersion"))
                .flinkImage(runtime.getSettings().getString("flinkImage"))
                .resources(settings.getJSONObject("resources"))
                .pyArchives(runtime.getSettings().getString("pyArchives"))
                .pyClientExecutable(runtime.getSettings().getString("pyClientExecutable"))
                .additionalDependencies(additionalDependencies)
                .extAdditionalDependencies(runtime.getSettings().getJSONArray("additionalDependencies"))
                .pythonScriptName(getScriptName(job.getId()))
                .execParams(execParams)
                .build();
        return generateDeployment(deploymentParam);
    }

    public SreworksStreamJobRuntimeDTO addRuntime(StreamJobRuntimeCreateParam param) throws Exception {
        SreworksStreamJobRuntime runtime = param.init();
        runtime = sreworksStreamJobRuntimeRepository.saveAndFlush(runtime);
        return new SreworksStreamJobRuntimeDTO(runtime);
    }

    public Page<SreworksStreamJobRuntimeDTO> runtimes(Pageable pageable) throws Exception {
        Page<SreworksStreamJobRuntime> runtimes = sreworksStreamJobRuntimeRepository.findAll(pageable);

        return runtimes.map(runtime -> {
            SreworksStreamJobRuntimeDTO runtimeDTO = new SreworksStreamJobRuntimeDTO(runtime);
            return runtimeDTO;
        });
    }

    public SreworksStreamJobRuntimeDTO runtimeGetById(Long runtimeId){
        SreworksStreamJobRuntime runtime = sreworksStreamJobRuntimeRepository.findFirstById(runtimeId);
        return  new SreworksStreamJobRuntimeDTO(runtime);
    }

    public SreworksStreamJobDTO importFile(StreamJobCreateParam param, File zipFile) throws Exception {
        Path jobFiles = unzipFile(zipFile.getAbsolutePath());
        JSONObject options = new JSONObject();
        if (Files.exists(jobFiles.resolve("template.py"))){
            log.info("find template.py in {}", zipFile.getAbsolutePath());
            String templateContent = new String(Files.readAllBytes(Paths.get(jobFiles.resolve("template.py").toFile().getAbsolutePath())));
            options.put("template", templateContent);
        } else {
            log.info("not find template.py in {}", zipFile.getAbsolutePath());
        }
        if (Files.exists(jobFiles.resolve("settings.json"))){
            String settingsJson = new String(Files.readAllBytes(Paths.get(jobFiles.resolve("settings.json").toFile().getAbsolutePath())));
            options.put("settings", JSONObject.parseObject(settingsJson));
        }
        param.setOptions(options);
        SreworksStreamJobDTO job = create(param);

        // 导入block
        Path sourcesDirectory = jobFiles.resolve("sources");
        if (Files.exists(sourcesDirectory) && Files.isDirectory(sourcesDirectory)) {
            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(sourcesDirectory)) {
                for (Path file : directoryStream) {
                    JSONObject content = JSONObject.parseObject(Files.readString(Paths.get(file.toFile().getAbsolutePath())));
                    StreamJobSourceCreateParam request = StreamJobSourceCreateParam.builder()
                            .creator(param.getCreator())
                            .operator(param.getOperator())
                            .options(content.getJSONArray("options"))
                            .columns(content.getJSONArray("columns"))
                            .type(content.getString("sourceType"))
                            .sourceName(file.getFileName().toString().replaceFirst("\\.[^.]+$", ""))
                            .build();
                    streamJobBlockService.addSource(job.getId(), param.getAppId(), request);
                }
            }
        }

        Path sinksDirectory = jobFiles.resolve("sinks");
        if (Files.exists(sinksDirectory) && Files.isDirectory(sinksDirectory)) {
            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(sinksDirectory)) {
                for (Path file : directoryStream) {
                    JSONObject content = JSONObject.parseObject(Files.readString(Paths.get(file.toFile().getAbsolutePath())));
                    StreamJobSinkCreateParam request = StreamJobSinkCreateParam.builder()
                            .creator(param.getCreator())
                            .operator(param.getOperator())
                            .options(content.getJSONArray("options"))
                            .columns(content.getJSONArray("columns"))
                            .type(content.getString("sinkType"))
                            .sinkName(file.getFileName().toString().replaceFirst("\\.[^.]+$", ""))
                            .build();
                    streamJobBlockService.addSink(job.getId(), param.getAppId(), request);
                }
            }
        }

        Path pythonsDirectory = jobFiles.resolve("pythons");
        if (Files.exists(pythonsDirectory) && Files.isDirectory(pythonsDirectory)) {
            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(pythonsDirectory)) {
                for (Path file : directoryStream) {
                    String content = Files.readString(Paths.get(file.toFile().getAbsolutePath()));
                    StreamJobPythonCreateParam request = StreamJobPythonCreateParam.builder()
                            .creator(param.getCreator())
                            .operator(param.getOperator())
                            .scriptContent(content)
                            .scriptName(file.getFileName().toString().replaceFirst("\\.[^.]+$", ""))
                            .build();
                    streamJobBlockService.addPython(job.getId(), param.getAppId(), request);
                }
            }
        }

        return job;
    }

    public File exportFile(SreworksStreamJobDTO job) throws Exception {
        /**
         *    ├── sinks
         *    │    ├── data_output.json
         *    │    └── data_output2.json
         *    ├── sources
         *    │    └── log_input.json
         *    ├── pythons
         *    │    ├── test1.py
         *    │    └── test2.py
         *    ├── template.py
         *    └── settings.json
         */

        Path tmpDir = Files.createTempDirectory("stream-job");
        File zipFile = Files.createTempFile("stream-job", ".zip").toFile();
        File sinks = new File(tmpDir.toFile(), "sinks");
        sinks.mkdir();
        File sources = new File(tmpDir.toFile(), "sources");
        sources.mkdir();
        File pythons = new File(tmpDir.toFile(), "pythons");
        pythons.mkdir();
        List<SreworksStreamJobBlockDTO> blocks = streamJobBlockService.listByStreamJobId(job.getId());
        for (SreworksStreamJobBlockDTO block : blocks) {
            if(StringUtils.equals(block.getBlockType(), "source")){
                createAndWriteFile(sources, block.getName() + ".json", JSON.toJSONString(block.getData(), SerializerFeature.PrettyFormat, SerializerFeature.SortField));
            }else if(StringUtils.equals(block.getBlockType(), "sink")) {
                createAndWriteFile(sinks, block.getName() + ".json", JSON.toJSONString(block.getData(), SerializerFeature.PrettyFormat, SerializerFeature.SortField));
            }else if(StringUtils.equals(block.getBlockType(), "python")) {
                createAndWriteFile(pythons, block.getName() + ".py", block.getData().getString("content"));
            }
        }
        createAndWriteFile(tmpDir.toFile(), "template.py", job.getOptions().getString("template"));
        createAndWriteFile(
                tmpDir.toFile(),
                "settings.json",
                JSON.toJSONString(job.getOptions().getJSONObject("settings"), SerializerFeature.PrettyFormat, SerializerFeature.SortField)
        );
        zipDirectory(tmpDir, zipFile.getAbsolutePath());
        return zipFile;
    }

    public static Path unzipFile(String zipFilePath) throws IOException {
        Path tmpDir = Files.createTempDirectory("job");
        String destDir = tmpDir.toString();
        FileInputStream fis;
        //buffer for read and write data to file
        byte[] buffer = new byte[1024];
        try {
            fis = new FileInputStream(zipFilePath);
            ZipInputStream zis = new ZipInputStream(fis);
            ZipEntry ze = zis.getNextEntry();
            while(ze != null){
                String fileName = ze.getName();
                File newFile = new File(destDir + File.separator + fileName);
                System.out.println("Unzipping to "+newFile.getAbsolutePath());
                //create directories for sub directories in zip
                new File(newFile.getParent()).mkdirs();
                FileOutputStream fos = new FileOutputStream(newFile);
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                //close this ZipEntry
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            //close last ZipEntry
            zis.closeEntry();
            zis.close();
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return tmpDir;
    }

    private static void createAndWriteFile(File folder, String fileName, String content) throws IOException {
        File file = new File(folder, fileName);
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    private static void zipDirectory(Path sourceDir, String zipFilePath) throws IOException {
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(Paths.get(zipFilePath)))) {
            Files.walk(sourceDir)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        try {
                            String relativePath = sourceDir.relativize(path).toString();
                            zipOutputStream.putNextEntry(new ZipEntry(relativePath));
                            Files.copy(path, zipOutputStream);
                            zipOutputStream.closeEntry();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        }
    }


}
