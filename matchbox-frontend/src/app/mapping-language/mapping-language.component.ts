import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { FhirConfigService } from '../fhirConfig.service';
import { UntypedFormControl } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import FhirClient from 'fhir-kit-client';
import debug from 'debug';

@Component({
    selector: 'app-mapping-language',
    templateUrl: './mapping-language.component.html',
    styleUrls: ['./mapping-language.component.scss'],
    standalone: false
})
export class MappingLanguageComponent implements OnInit {
  static log = debug('app:');
  public source: UntypedFormControl;
  public map: UntypedFormControl;
  public structureMap: any;
  public transformed: any;
  client: FhirClient;
  errMsg: string;

  operationOutcome: fhir.r4.OperationOutcome;
  operationOutcomeTransformed: fhir.r4.OperationOutcome;

  constructor(
    private cd: ChangeDetectorRef,
    private data: FhirConfigService
  ) {
    this.client = data.getFhirClient();
    this.source = new UntypedFormControl();
    this.map = new UntypedFormControl();
    this.structureMap = null;
    this.map.valueChanges.pipe(debounceTime(1000), distinctUntilChanged()).subscribe((mapText) => {
      MappingLanguageComponent.log('create StructureMap');
      this.client
        .create({
          resourceType: 'StructureMap',
          body: mapText,
          headers: {
            accept: 'application/fhir+json',
            'content-type': 'text/fhir-mapping',
          },
        })
        .then((response) => {
          this.operationOutcome = null;
          this.structureMap = response;
          this.transform();
        })
        .catch((error) => {
          // {"response":{"status":500,"data":{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"processing","diagnostics":"Error @1, 1: Found \"asdfasdf\" expecting \"map\""}]}},"config":{"method":"post","url":"https://test.ahdis.ch/r4/StructureMap","headers":{}}}
          this.structureMap = null;
          this.operationOutcome = error.response.data;
        });
    });

    this.source.valueChanges
      .pipe(debounceTime(1000), distinctUntilChanged())
      .subscribe((sourceText) => this.transform());
  }

  transform() {
    MappingLanguageComponent.log('transform Source');
    let res: fhir.r4.Resource = JSON.parse(this.source.value);
    if (this.structureMap != null) {
      this.client
        .operation({
          name: 'transform?source=' + encodeURIComponent(this.structureMap.url),
          resourceType: 'StructureMap',
          input: res,
        })
        .then((response) => {
          this.operationOutcomeTransformed = null;
          this.transformed = response;
        })
        .catch((error) => {
          this.transformed = null;
          this.operationOutcomeTransformed = error.response.data;
        });
    }
  }

  ngOnInit() {}

  fileSource(event) {
    const reader = new FileReader();

    if (event.target.files && event.target.files.length) {
      const [file] = event.target.files;
      reader.readAsText(file);
      reader.onload = () => {
        this.source.setValue(reader.result);
        // need to run CD since file load runs outside of zone
        this.cd.markForCheck();
      };
    }
  }

  fileChange(event) {
    const reader = new FileReader();

    if (event.target.files && event.target.files.length) {
      const [file] = event.target.files;
      reader.readAsText(file);
      reader.onload = () => {
        this.map.setValue(reader.result);
        // need to run CD since file load runs outside of zone
        this.cd.markForCheck();
      };
    }
  }
}
