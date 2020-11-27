import {NgModule} from '@angular/core';
import {LocationRoutingModule} from './location-routing.module';
import {AddLocationComponent} from './add-location/add-location.component';
import {LocationDetailsComponent} from './location-details/location-details.component';
import {LocationListComponent} from './location-list/location-list.component';
import {NG2DataTableModule} from 'angular2-datatable-pagination';
import {FormsModule} from '@angular/forms';
import {CommonModule} from '@angular/common';
import {EditLocationComponent} from './edit-location/edit-location.component';

@NgModule({
  declarations: [
    AddLocationComponent,
    LocationDetailsComponent,
    LocationListComponent,
    EditLocationComponent,
  ],
  imports: [
    FormsModule,
    CommonModule,
    LocationRoutingModule,
    NG2DataTableModule,
  ],
})
export class LocationModule {}
