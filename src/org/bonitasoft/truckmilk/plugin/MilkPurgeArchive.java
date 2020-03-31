package org.bonitasoft.truckmilk.plugin;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

import org.bonitasoft.engine.api.ProcessAPI;
import org.bonitasoft.engine.bpm.process.ArchivedProcessInstance;
import org.bonitasoft.engine.bpm.process.ArchivedProcessInstancesSearchDescriptor;
import org.bonitasoft.engine.exception.DeletionException;
import org.bonitasoft.engine.exception.SearchException;
import org.bonitasoft.engine.search.Order;
import org.bonitasoft.engine.search.SearchOptionsBuilder;
import org.bonitasoft.engine.search.SearchResult;
import org.bonitasoft.log.event.BEvent;
import org.bonitasoft.log.event.BEvent.Level;
import org.bonitasoft.log.event.BEventFactory;
import org.bonitasoft.truckmilk.engine.MilkPlugIn;
import org.bonitasoft.truckmilk.engine.MilkPlugInToolbox;
import org.bonitasoft.truckmilk.engine.MilkPlugInToolbox.DelayResult;
import org.bonitasoft.truckmilk.engine.MilkPlugInToolbox.ListProcessesResult;
import org.bonitasoft.truckmilk.engine.MilkPlugInDescription;
import org.bonitasoft.truckmilk.engine.MilkPlugInDescription.CATEGORY;
import org.bonitasoft.truckmilk.engine.MilkPlugInDescription.JOBSTOPPER;
import org.bonitasoft.truckmilk.engine.MilkJobOutput;
import org.bonitasoft.truckmilk.job.MilkJobExecution;
import org.bonitasoft.truckmilk.toolbox.TypesCast;
import org.bonitasoft.truckmilk.job.MilkJob.ExecutionStatus;
public class MilkPurgeArchive extends MilkPlugIn {

    static Logger logger = Logger.getLogger(MilkPurgeArchive.class.getName());


    private static BEvent eventDeletionSuccess = new BEvent(MilkPurgeArchive.class.getName(), 1, Level.SUCCESS,
            "Deletion done with success", "Archived Cases are deleted with success");

    private static BEvent eventDeletionFailed = new BEvent(MilkPurgeArchive.class.getName(), 2, Level.ERROR,
            "Error during deletion", "An error arrived during the deletion of archived cases", "Cases are not deleted", "Check the exception");

    private static BEvent eventSearchFailed = new BEvent(MilkPurgeArchive.class.getName(), 3, Level.ERROR,
            "Search failed", "Search failed task return an error", "No retry can be performed", "Check the error");

    private static PlugInParameter cstParamDelayInDay = PlugInParameter.createInstance("delayinday", "Delay in day", TypeParameter.DELAY, MilkPlugInToolbox.DELAYSCOPE.MONTH + ":3", "The case must be older than this number, in days. 0 means all archived case is immediately in the perimeter");
    private static PlugInParameter cstParamProcessFilter = PlugInParameter.createInstance("processfilter", "Process Filter", TypeParameter.ARRAYPROCESSNAME, null, "Give a list of process name. Name must be exact, no version is given (all versions will be purged)");

    public MilkPurgeArchive() {
        super(TYPE_PLUGIN.EMBEDED);
    }

    /**
     * check the environment : for the milkEmailUsersTasks, we require to be able to send an email
     */
    public List<BEvent> checkPluginEnvironment(MilkJobExecution jobExecution) {
        return new ArrayList<>();
    }

    /**
     * check the Job's environment
     */
    public List<BEvent> checkJobEnvironment(MilkJobExecution jobExecution) {
        return new ArrayList<>();
    }


    @Override
    public MilkJobOutput execute(MilkJobExecution jobExecution) {
        MilkJobOutput plugTourOutput = jobExecution.getMilkJobOutput();

        ProcessAPI processAPI = jobExecution.getApiAccessor().getProcessAPI();
        // get Input 

        SearchOptionsBuilder searchActBuilder = new SearchOptionsBuilder(0, (int) jobExecution.getJobStopAfterMaxItems() + 1);
        SearchResult<ArchivedProcessInstance> searchArchivedProcessInstance;

        try {
            ListProcessesResult listProcessResult = MilkPlugInToolbox.completeListProcess(jobExecution, cstParamProcessFilter, false, searchActBuilder, ArchivedProcessInstancesSearchDescriptor.PROCESS_DEFINITION_ID, jobExecution.getApiAccessor().getProcessAPI());

            if (BEventFactory.isError(listProcessResult.listEvents)) {
                // filter given, no process found : stop now
                plugTourOutput.addEvents(listProcessResult.listEvents);
                plugTourOutput.executionStatus = ExecutionStatus.BADCONFIGURATION;
                return plugTourOutput;
            }

            DelayResult delayResult = MilkPlugInToolbox.getTimeFromDelay(jobExecution, cstParamDelayInDay, new Date(), false);
            if (BEventFactory.isError(delayResult.listEvents)) {
                plugTourOutput.addEvents(delayResult.listEvents);
                plugTourOutput.executionStatus = ExecutionStatus.ERROR;
                return plugTourOutput;
            }
            long timeSearch = delayResult.delayDate.getTime();


            // Now, search items to delete
            searchActBuilder.lessOrEquals(ArchivedProcessInstancesSearchDescriptor.ARCHIVE_DATE, timeSearch);
            searchActBuilder.sort(ArchivedProcessInstancesSearchDescriptor.ARCHIVE_DATE, Order.ASC);

            searchArchivedProcessInstance = processAPI.searchArchivedProcessInstances(searchActBuilder.done());
            if (searchArchivedProcessInstance.getCount() == 0) {
                plugTourOutput.executionStatus = ExecutionStatus.SUCCESSNOTHING;
                plugTourOutput.nbItemsProcessed = 0;
                return plugTourOutput;
            }
        } catch (SearchException e1) {
            plugTourOutput.addEvent(new BEvent(eventSearchFailed, e1, ""));
            plugTourOutput.executionStatus = ExecutionStatus.ERROR;
            return plugTourOutput;
        }
        
        
        // do the purge now
        Long beginTime = System.currentTimeMillis();
        List<Long> sourceProcessInstanceIds = new ArrayList<>();
        try {
        for (ArchivedProcessInstance archivedProcessInstance : searchArchivedProcessInstance.getResult()) {
            if (jobExecution.pleaseStop())
                break;
            // proceed page per page
            sourceProcessInstanceIds.add(archivedProcessInstance.getSourceObjectId());
            if (sourceProcessInstanceIds.size() == 50)
            {
                processAPI.deleteArchivedProcessInstancesInAllStates(sourceProcessInstanceIds);
                plugTourOutput.nbItemsProcessed += sourceProcessInstanceIds.size();
                sourceProcessInstanceIds.clear();
            }
        }
        // reliquat
        if (! sourceProcessInstanceIds.isEmpty())
        {
            processAPI.deleteArchivedProcessInstancesInAllStates(sourceProcessInstanceIds);
            plugTourOutput.nbItemsProcessed += sourceProcessInstanceIds.size();
        }        
        Long endTime = System.currentTimeMillis();
        
        plugTourOutput.addEvent(new BEvent(eventDeletionSuccess, "Purge:" + plugTourOutput.nbItemsProcessed+" in "+TypesCast.getHumanDuration(endTime-beginTime, true)));
        plugTourOutput.executionStatus = ExecutionStatus.SUCCESS;

        } catch (DeletionException e) {
            logger.severe("Error Delete Archived ProcessInstance=[" + sourceProcessInstanceIds + "] Error[" + e.getMessage() + "]");
            plugTourOutput.executionStatus = ExecutionStatus.ERROR;
            plugTourOutput.addEvent(new BEvent(eventDeletionFailed, e, "Purge:" + sourceProcessInstanceIds));
        }

        return plugTourOutput;
    }

    @Override
    public MilkPlugInDescription getDefinitionDescription() {
        MilkPlugInDescription plugInDescription = new MilkPlugInDescription();
        plugInDescription.setName( "PurgeArchivedCase");
        plugInDescription.setLabel( "Purge Archived Case" );
        plugInDescription.setCategory( CATEGORY.CASES);
        plugInDescription.setDescription( "Purge all archived case according the filter. Filter based on different process, and purge cases older than the delai. At each round, a maximum case are deleted. If the maximum is over than 20, it's reduce to 20.");
        plugInDescription.addParameter(cstParamDelayInDay);
        plugInDescription.addParameter(cstParamProcessFilter);
        plugInDescription.setStopJob(JOBSTOPPER.BOTH);
        plugInDescription.setJobStopMaxItems( 100000 );
        return plugInDescription;
    }
}
