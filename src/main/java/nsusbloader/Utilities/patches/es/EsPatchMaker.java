/*
    Copyright 2018-2022 Dmitry Isaenko
     
    This file is part of NS-USBloader.

    NS-USBloader is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NS-USBloader is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NS-USBloader.  If not, see <https://www.gnu.org/licenses/>.
 */
package nsusbloader.Utilities.patches.es;

import libKonogonka.KeyChainHolder;
import libKonogonka.Tools.NCA.NCAProvider;
import nsusbloader.ModelControllers.CancellableRunnable;
import nsusbloader.ModelControllers.ILogPrinter;
import nsusbloader.ModelControllers.Log;
import nsusbloader.NSLDataTypes.EModule;
import nsusbloader.NSLDataTypes.EMsgType;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class EsPatchMaker extends CancellableRunnable {
    private int THREADS_POOL_SIZE = 4;
    private final ILogPrinter logPrinter;
    private final String pathToFirmware;
    private final String pathToKeysFile;
    private final String saveTo;

    private File firmware;
    private KeyChainHolder keyChainHolder;
    private ExecutorService executorService;
    private List<String> ncaFilesList; // inside the folder

    private boolean oneLinerStatus = false;
    
    public EsPatchMaker(String pathToFirmware, String pathToKeysFile, String saveTo){
        this.logPrinter = Log.getPrinter(EModule.PATCHES); //TODO: UNCOMMENT
        /*
        this.logPrinter = new ILogPrinter() {
            @Override
            public void print(String message, EMsgType type) throws InterruptedException {}
            @Override
            public void updateProgress(Double value) throws InterruptedException {}
            @Override
            public void update(HashMap<String, File> nspMap, EFileStatus status) {}
            @Override
            public void update(File file, EFileStatus status) {}
            @Override
            public void updateOneLinerStatus(boolean status) {}
            @Override
            public void close() {}
        };
         */
        this.pathToFirmware = pathToFirmware;
        this.pathToKeysFile = pathToKeysFile;
        this.saveTo = saveTo;
    }

    @Override
    public void run() {
        try {
            logPrinter.print("..:: Make ES Patches ::..", EMsgType.INFO);
            receiveFirmware();
            buildKeyChainHolder();
            receiveNcaFileNamesList();
            adjustThreadsPoolSize();
            createPool();
            executePool();
        }
        catch (Exception e){
            e.printStackTrace();
            try{
                logPrinter.print(e.getMessage(), EMsgType.FAIL);
            } catch (Exception ignore){}
        }
        finally {
            logPrinter.updateOneLinerStatus(oneLinerStatus);
            logPrinter.close();
        }
    }
    private void receiveFirmware() throws Exception{
        logPrinter.print("Looking at firmware", EMsgType.INFO);
        this.firmware = new File(pathToFirmware);
    }
    private void buildKeyChainHolder() throws Exception{
        logPrinter.print("Reading keys", EMsgType.INFO);
        this.keyChainHolder = new KeyChainHolder(pathToKeysFile, null);
    }
    private void receiveNcaFileNamesList() throws Exception{
        logPrinter.print("Collecting NCA files", EMsgType.INFO);
        String[] fileNamesArray = firmware.list((File directory, String file) -> ( ! file.endsWith(".cnmt.nca") && file.endsWith(".nca")));
        ncaFilesList = Arrays.asList(Objects.requireNonNull(fileNamesArray));
        if (ncaFilesList.size() == 0)
            throw new Exception("No NCA files found in firmware folder");
    }
    private void adjustThreadsPoolSize(){
        if (ncaFilesList.size() < 4)
            THREADS_POOL_SIZE = ncaFilesList.size();
    }

    private void createPool() throws Exception{
        logPrinter.print("Creating sub-tasks pool", EMsgType.INFO);
        this.executorService = Executors.newFixedThreadPool(
                THREADS_POOL_SIZE,
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private void executePool() throws Exception{ //TODO: FIX. Exceptions thrown only by logPrinter
        try {
            logPrinter.print("Executing sub-tasks pool", EMsgType.INFO);
            List<Future<NCAProvider>> futuresResults = executorService.invokeAll(getSubTasksCollection());
            for (Future<NCAProvider> future : futuresResults){
                NCAProvider ncaProvider = future.get();
                if (ncaProvider != null) {
                    makePatches(ncaProvider);
                    break;
                }
            }
            executorService.shutdown();
        }
        catch (InterruptedException ie){
            executorService.shutdownNow();
            boolean interruptedSuccessfully = false;
            try {
                interruptedSuccessfully = executorService.awaitTermination(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException awaitInterrupt){
                logPrinter.print("Force interrupting task...", EMsgType.WARNING);
            }
            logPrinter.print("Task interrupted "+(interruptedSuccessfully?"successfully":"with some issues"), EMsgType.WARNING);
        }
        catch (Exception e){
            e.printStackTrace();
            logPrinter.print("Task failed: "+e.getMessage(), EMsgType.FAIL);
        }
    }

    private void makePatches(NCAProvider ncaProvider) throws Exception{
        logPrinter.print(String.format("File found: .."+File.separator+"%s"+File.separator+"%s",
                        ncaProvider.getFile().getParentFile().getName(), ncaProvider.getFile().getName())
                , EMsgType.INFO);
        new EsPatch(ncaProvider, saveTo, logPrinter);
        oneLinerStatus = true;
    }
    private List<Callable<NCAProvider>> getSubTasksCollection() throws Exception{
        logPrinter.print("Forming sub-tasks collection", EMsgType.INFO);
        List<Callable<NCAProvider>> subTasks = new ArrayList<>();

        int ncaPerThreadAmount = ncaFilesList.size() / THREADS_POOL_SIZE;
        Iterator<String> iterator = ncaFilesList.listIterator();

        for (int i = 1; i < THREADS_POOL_SIZE; i++){
            Callable<NCAProvider> task = new EsNcaSearchTask(getNextSet(iterator, ncaPerThreadAmount));
            subTasks.add(task);
        }

        Callable<NCAProvider> task = new EsNcaSearchTask(getNextSet(iterator,
                ncaFilesList.size() % THREADS_POOL_SIZE == 0 ? ncaPerThreadAmount : ncaPerThreadAmount+1));
        subTasks.add(task);
        return subTasks;
    }
    private List<NCAProvider>  getNextSet(Iterator<String> iterator, int amount) throws Exception{
        List<NCAProvider> ncas = new ArrayList<>();
        for (int j = 0; j < amount; j++){
            String ncaFileName = iterator.next();
            File nca = new File(firmware.getAbsolutePath()+File.separator+ncaFileName);
            NCAProvider provider = new NCAProvider(nca, keyChainHolder.getRawKeySet());
            ncas.add(provider);
        }
        return ncas;
    }
}